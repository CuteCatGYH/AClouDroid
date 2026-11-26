#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <windows.h>

#include "http_server.hpp"
#include "config.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <memory>
#include <thread>

namespace anisette {

namespace {

constexpr size_t kMaxHeaderBytes = 8192;
constexpr int kReadTimeoutMs = 10000;
constexpr int kWriteTimeoutMs = 10000;
constexpr int kMaxConcurrentConns = 64;
constexpr size_t kRateLimitMax = 30;
constexpr auto kRateLimitWindow = std::chrono::seconds(60);

constexpr size_t kBruteForceThreshold = 10;
constexpr auto kBruteForceWindow = std::chrono::minutes(10);
constexpr auto kBruteForceBlockDuration = std::chrono::minutes(30);
constexpr size_t kBruteForceMaxTrackedIps = 4096;

std::string format_log_utc_now() {
    SYSTEMTIME st{};
    GetSystemTime(&st);
    char buf[32];
    std::snprintf(
        buf, sizeof(buf),
        "%04u-%02u-%02uT%02u:%02u:%02uZ",
        st.wYear, st.wMonth, st.wDay,
        st.wHour, st.wMinute, st.wSecond);
    return std::string(buf);
}

bool send_all(SOCKET s, const char* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        int n = send(s, data + sent, static_cast<int>(len - sent), 0);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

struct ClientIO {
    SOCKET sock = INVALID_SOCKET;
    TlsConnection* tls = nullptr;

    // Returns >0 bytes read, 0 on EOF, -1 on error, -2 on timeout.
    int read_some(char* buf, size_t max_len, std::chrono::steady_clock::time_point deadline) {
        if (tls) {
            return tls->read_some(buf, max_len, deadline);
        }
        auto now = std::chrono::steady_clock::now();
        if (now >= deadline) return -2;
        auto rem_ms = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
        if (rem_ms <= 0) rem_ms = 1;

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(sock, &rfds);
        timeval tv{};
        tv.tv_sec = static_cast<long>(rem_ms / 1000);
        tv.tv_usec = static_cast<long>((rem_ms % 1000) * 1000);

        int sel = select(0, &rfds, nullptr, nullptr, &tv);
        if (sel == 0) return -2;
        if (sel < 0) return -1;

        int n = recv(sock, buf, static_cast<int>(max_len), 0);
        if (n == 0) return 0;
        if (n < 0) return -1;
        return n;
    }

    bool write_all(const char* data, size_t len) {
        if (tls) {
            return tls->write_all(data, len);
        }
        return send_all(sock, data, len);
    }
};

const char* reason_phrase(int status) {
    switch (status) {
        case 200: return "OK";
        case 400: return "Bad Request";
        case 404: return "Not Found";
        case 405: return "Method Not Allowed";
        case 408: return "Request Timeout";
        case 429: return "Too Many Requests";
        case 431: return "Request Header Fields Too Large";
        case 500: return "Internal Server Error";
        case 503: return "Service Unavailable";
        default:  return "Error";
    }
}

void send_http_response(
    ClientIO& io,
    int status,
    const char* content_type,
    const std::string& body,
    bool head_only,
    const char* extra_headers = nullptr) {
    std::string resp;
    resp.reserve(256 + (head_only ? 0 : body.size()));
    resp += "HTTP/1.1 " + std::to_string(status) + " " + reason_phrase(status) + "\r\n";
    if (content_type && content_type[0] != '\0') {
        resp += std::string("Content-Type: ") + content_type + "\r\n";
    }
    resp += "Content-Length: " + std::to_string(body.size()) + "\r\n";
    if (extra_headers && extra_headers[0] != '\0') {
        resp += extra_headers;
    }
    resp += "Connection: close\r\n\r\n";
    if (!head_only && !body.empty()) {
        resp += body;
    }
    io.write_all(resp.data(), resp.size());
}

enum class ReadResult {
    Ok,
    Timeout,
    TooLarge,
    Error
};

ReadResult read_http_headers(
    ClientIO& io,
    std::chrono::steady_clock::time_point deadline,
    std::string& out) {
    out.clear();
    out.reserve(1024);

    char chunk[1024];

    for (;;) {
        size_t space = kMaxHeaderBytes - out.size();
        if (space == 0) return ReadResult::TooLarge;
        size_t to_read = std::min(space, sizeof(chunk));
        int n = io.read_some(chunk, to_read, deadline);
        if (n == -2) return ReadResult::Timeout;
        if (n <= 0) return ReadResult::Error;

        out.append(chunk, static_cast<size_t>(n));
        if (out.find("\r\n\r\n") != std::string::npos || out.find("\n\n") != std::string::npos) {
            return ReadResult::Ok;
        }
        if (out.size() >= kMaxHeaderBytes) {
            return ReadResult::TooLarge;
        }
    }
}

bool is_ipv4_mapped_ipv6(const in6_addr& addr6) {
    for (int i = 0; i < 10; ++i) {
        if (addr6.s6_addr[i] != 0) return false;
    }
    return addr6.s6_addr[10] == 0xFF && addr6.s6_addr[11] == 0xFF;
}

std::string sockaddr_to_ip(const sockaddr_storage& ss) {
    char buf[INET6_ADDRSTRLEN]{};
    if (ss.ss_family == AF_INET) {
        const auto* sa = reinterpret_cast<const sockaddr_in*>(&ss);
        if (inet_ntop(AF_INET, &sa->sin_addr, buf, sizeof(buf))) {
            return std::string(buf);
        }
    } else if (ss.ss_family == AF_INET6) {
        const auto* sa6 = reinterpret_cast<const sockaddr_in6*>(&ss);
        if (is_ipv4_mapped_ipv6(sa6->sin6_addr)) {
            if (inet_ntop(AF_INET, &sa6->sin6_addr.s6_addr[12], buf, sizeof(buf))) {
                return std::string(buf);
            }
        }
        if (inet_ntop(AF_INET6, &sa6->sin6_addr, buf, sizeof(buf))) {
            return std::string(buf);
        }
    }
    return "unknown";
}

} // namespace

bool parse_listen_addr(const std::string& input, ListenEndpoint& out) {
    if (input.empty()) return false;
    std::string host;
    std::string port_str;

    if (input.front() == '[') {
        size_t close = input.find(']');
        if (close == std::string::npos || close + 1 >= input.size() || input[close + 1] != ':') {
            return false;
        }
        host = input.substr(1, close - 1);
        port_str = input.substr(close + 2);
    } else {
        size_t colon = input.rfind(':');
        if (colon == std::string::npos) {
            return false;
        }
        host = input.substr(0, colon);
        port_str = input.substr(colon + 1);
    }

    if (host.empty()) host = "0.0.0.0";
    if (port_str.empty()) return false;
    for (unsigned char c : port_str) {
        if (!std::isdigit(c)) return false;
    }
    unsigned long p = std::strtoul(port_str.c_str(), nullptr, 10);
    if (p == 0 || p > 65535) return false;

    out.host = std::move(host);
    out.port = static_cast<uint16_t>(p);
    return true;
}

bool is_loopback_host(const std::string& host) {
    std::string lower = host;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    if (lower == "localhost" || lower == "::1" || lower == "127.0.0.1") {
        return true;
    }
    in_addr addr4{};
    if (inet_pton(AF_INET, host.c_str(), &addr4) == 1) {
        uint32_t ip = ntohl(addr4.s_addr);
        return (ip >> 24) == 127;
    }
    in6_addr addr6{};
    if (inet_pton(AF_INET6, host.c_str(), &addr6) == 1) {
        static constexpr uint8_t kLoop6[16] = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
        if (std::memcmp(addr6.s6_addr, kLoop6, 16) == 0) {
            return true;
        }
        if (is_ipv4_mapped_ipv6(addr6) && addr6.s6_addr[12] == 127) {
            return true;
        }
    }
    return false;
}

std::vector<std::string> get_non_loopback_ipv4_addresses() {
    std::vector<std::string> result;
    ULONG bufSize = 16384;
    std::vector<uint8_t> buf(bufSize);

    ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    ULONG rc = GetAdaptersAddresses(
        AF_INET, flags, nullptr,
        reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data()), &bufSize);
    if (rc == ERROR_BUFFER_OVERFLOW) {
        buf.resize(bufSize);
        rc = GetAdaptersAddresses(
            AF_INET, flags, nullptr,
            reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data()), &bufSize);
    }
    if (rc != NO_ERROR) {
        return result;
    }

    for (auto* adapter = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buf.data());
         adapter != nullptr;
         adapter = adapter->Next) {
        if (adapter->OperStatus != IfOperStatusUp) continue;
        if (adapter->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;

        for (auto* ua = adapter->FirstUnicastAddress; ua != nullptr; ua = ua->Next) {
            if (!ua->Address.lpSockaddr || ua->Address.lpSockaddr->sa_family != AF_INET) {
                continue;
            }
            const auto* sa = reinterpret_cast<const sockaddr_in*>(ua->Address.lpSockaddr);
            uint32_t ip = ntohl(sa->sin_addr.s_addr);
            uint8_t first = static_cast<uint8_t>(ip >> 24);
            uint8_t second = static_cast<uint8_t>((ip >> 16) & 0xFF);
            // Skip loopback 127.0.0.0/8 and link-local APIPA 169.254.0.0/16
            if (first == 127 || (first == 169 && second == 254)) continue;

            char ipStr[INET_ADDRSTRLEN]{};
            if (inet_ntop(AF_INET, &sa->sin_addr, ipStr, sizeof(ipStr))) {
                std::string s(ipStr);
                if (std::find(result.begin(), result.end(), s) == result.end()) {
                    result.push_back(std::move(s));
                }
            }
        }
    }
    return result;
}

HttpServer::HttpServer(
    ListenEndpoint endpoint,
    std::string token,
    AnisetteProvider& provider,
    const TlsContext* tls)
    : endpoint_(std::move(endpoint)),
      token_(std::move(token)),
      provider_(provider),
      tls_(tls) {}

HttpServer::~HttpServer() {
    stop();
}

bool HttpServer::bind_and_listen(std::string& err) {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    hints.ai_flags = AI_PASSIVE;

    std::string port_str = std::to_string(endpoint_.port);
    addrinfo* res = nullptr;
    int gai = getaddrinfo(endpoint_.host.c_str(), port_str.c_str(), &hints, &res);
    if (gai != 0 || !res) {
        err = "getaddrinfo failed for " + endpoint_.host + ":" + port_str;
        return false;
    }

    SOCKET sock = INVALID_SOCKET;
    int last_wsa_err = 0;
    for (addrinfo* p = res; p != nullptr; p = p->ai_next) {
        sock = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (sock == INVALID_SOCKET) {
            last_wsa_err = WSAGetLastError();
            continue;
        }
        int opt = 1;
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&opt), sizeof(opt));
        if (p->ai_family == AF_INET6) {
            int v6only = 0;
            setsockopt(sock, IPPROTO_IPV6, IPV6_V6ONLY, reinterpret_cast<const char*>(&v6only), sizeof(v6only));
        }
        if (bind(sock, p->ai_addr, static_cast<int>(p->ai_addrlen)) == 0) {
            break;
        }
        last_wsa_err = WSAGetLastError();
        closesocket(sock);
        sock = INVALID_SOCKET;
    }
    freeaddrinfo(res);

    if (sock == INVALID_SOCKET) {
        err = "failed to bind " + endpoint_.host + ":" + port_str +
              " (WSA error " + std::to_string(last_wsa_err) + ")";
        return false;
    }

    if (listen(sock, SOMAXCONN) == SOCKET_ERROR) {
        int e = WSAGetLastError();
        closesocket(sock);
        err = "listen failed (WSA error " + std::to_string(e) + ")";
        return false;
    }

    listen_sock_ = static_cast<uintptr_t>(sock);
    running_.store(true);
    return true;
}

void HttpServer::serve() {
    while (running_.load()) {
        sockaddr_storage ss{};
        int ss_len = sizeof(ss);
        SOCKET client = accept(static_cast<SOCKET>(listen_sock_),
                               reinterpret_cast<sockaddr*>(&ss), &ss_len);
        if (client == INVALID_SOCKET) {
            if (!running_.load()) break;
            continue;
        }

        if (active_conns_.fetch_add(1) >= kMaxConcurrentConns) {
            active_conns_.fetch_sub(1);
            if (!tls_) {
                ClientIO io{client, nullptr};
                send_http_response(io, 503, nullptr, "", false);
            }
            closesocket(client);
            continue;
        }

        std::string ip = sockaddr_to_ip(ss);
        std::thread([this, client, ip = std::move(ip)]() mutable {
            handle_client(static_cast<uintptr_t>(client), std::move(ip));
            active_conns_.fetch_sub(1);
        }).detach();
    }
}

void HttpServer::stop() {
    bool expected = true;
    if (running_.compare_exchange_strong(expected, false)) {
        SOCKET s = static_cast<SOCKET>(listen_sock_);
        if (s != INVALID_SOCKET) {
            listen_sock_ = static_cast<uintptr_t>(INVALID_SOCKET);
            closesocket(s);
        }
    }
}

bool HttpServer::check_rate_limit() {
    auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lk(rate_mu_);
    while (!anisette_timestamps_.empty() && (now - anisette_timestamps_.front()) >= kRateLimitWindow) {
        anisette_timestamps_.pop_front();
    }
    if (anisette_timestamps_.size() >= kRateLimitMax) {
        return false;
    }
    anisette_timestamps_.push_back(now);
    return true;
}

bool HttpServer::is_ip_blocked(const std::string& remote_ip) {
    if (is_loopback_host(remote_ip)) {
        return false;
    }
    auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lk(bf_mu_);
    auto it = bf_map_.find(remote_ip);
    if (it == bf_map_.end()) {
        return false;
    }
    if (it->second.blocked_until > now) {
        it->second.last_seen = now;
        return true;
    }
    if (it->second.blocked_until != std::chrono::steady_clock::time_point{}) {
        it->second.blocked_until = {};
        while (!it->second.failures.empty() && (now - it->second.failures.front()) >= kBruteForceWindow) {
            it->second.failures.pop_front();
        }
        if (it->second.failures.empty()) {
            bf_map_.erase(it);
        }
    }
    return false;
}

bool HttpServer::record_failed_auth(const std::string& remote_ip) {
    if (is_loopback_host(remote_ip)) {
        return false;
    }
    auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lk(bf_mu_);

    if (bf_map_.find(remote_ip) == bf_map_.end() && bf_map_.size() >= kBruteForceMaxTrackedIps) {
        // First prune expired entries.
        for (auto it = bf_map_.begin(); it != bf_map_.end(); ) {
            auto& st = it->second;
            while (!st.failures.empty() && (now - st.failures.front()) >= kBruteForceWindow) {
                st.failures.pop_front();
            }
            if (st.blocked_until <= now && st.failures.empty()) {
                it = bf_map_.erase(it);
            } else {
                ++it;
            }
        }
        // If still at capacity, evict the oldest entry (preferring unblocked).
        if (bf_map_.size() >= kBruteForceMaxTrackedIps) {
            auto victim = bf_map_.end();
            auto oldest_unblocked = bf_map_.end();
            for (auto it = bf_map_.begin(); it != bf_map_.end(); ++it) {
                if (victim == bf_map_.end() || it->second.last_seen < victim->second.last_seen) {
                    victim = it;
                }
                if (it->second.blocked_until <= now) {
                    if (oldest_unblocked == bf_map_.end() ||
                        it->second.last_seen < oldest_unblocked->second.last_seen) {
                        oldest_unblocked = it;
                    }
                }
            }
            auto to_erase = (oldest_unblocked != bf_map_.end()) ? oldest_unblocked : victim;
            if (to_erase != bf_map_.end()) {
                bf_map_.erase(to_erase);
            }
        }
    }

    auto& st = bf_map_[remote_ip];
    st.last_seen = now;
    while (!st.failures.empty() && (now - st.failures.front()) >= kBruteForceWindow) {
        st.failures.pop_front();
    }
    st.failures.push_back(now);
    if (st.failures.size() >= kBruteForceThreshold) {
        st.blocked_until = now + kBruteForceBlockDuration;
        st.failures.clear();
        return true;
    }
    return false;
}

void HttpServer::log_request(const std::string& remote_ip, const char* path_class, int status) {
    std::string ts = format_log_utc_now();
    std::lock_guard<std::mutex> lk(log_mu_);
    std::printf("%s %s %s %d\n", ts.c_str(), remote_ip.c_str(), path_class, status);
    std::fflush(stdout);
}

void HttpServer::handle_client(uintptr_t client_sock, std::string remote_ip) {
    SOCKET s = static_cast<SOCKET>(client_sock);
    DWORD timeout = static_cast<DWORD>(kWriteTimeoutMs);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout));
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout));

    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kReadTimeoutMs);

    std::unique_ptr<TlsConnection> tls_conn;
    ClientIO io{s, nullptr};

    struct ConnCloser {
        SOCKET sock;
        std::unique_ptr<TlsConnection>& tls;
        ~ConnCloser() {
            if (tls) {
                tls->shutdown();
            }
            closesocket(sock);
        }
    } closer{s, tls_conn};

    if (tls_ && tls_->is_loaded()) {
        tls_conn = std::make_unique<TlsConnection>(client_sock, *tls_);
        if (!tls_conn->handshake(deadline)) {
            return;
        }
        io.tls = tls_conn.get();
    }

    std::string raw;
    ReadResult rr = read_http_headers(io, deadline, raw);
    if (rr == ReadResult::Timeout) {
        send_http_response(io, 408, nullptr, "", false);
        log_request(remote_ip, "denied", 408);
        return;
    }
    if (rr == ReadResult::TooLarge) {
        send_http_response(io, 431, nullptr, "", false);
        log_request(remote_ip, "denied", 431);
        return;
    }
    if (rr != ReadResult::Ok) {
        return;
    }

    // Parse request line: METHOD SP TARGET SP HTTP/1.x
    size_t line_end = raw.find('\n');
    std::string req_line = (line_end == std::string::npos) ? raw : raw.substr(0, line_end);
    if (!req_line.empty() && req_line.back() == '\r') req_line.pop_back();

    size_t sp1 = req_line.find(' ');
    size_t sp2 = (sp1 == std::string::npos) ? std::string::npos : req_line.find(' ', sp1 + 1);

    // Brute-force guard: blocked IPs get 404 for EVERYTHING (even valid token or any method).
    if (is_ip_blocked(remote_ip)) {
        bool head_only = (sp1 != std::string::npos && req_line.compare(0, sp1, "HEAD") == 0);
        send_http_response(io, 404, nullptr, "", head_only);
        log_request(remote_ip, "blocked", 404);
        return;
    }

    if (sp1 == std::string::npos || sp2 == std::string::npos) {
        send_http_response(io, 400, nullptr, "", false);
        log_request(remote_ip, "denied", 400);
        return;
    }

    std::string method = req_line.substr(0, sp1);
    std::string target = req_line.substr(sp1 + 1, sp2 - sp1 - 1);

    if (method != "GET" && method != "HEAD") {
        send_http_response(io, 405, nullptr, "", false, "Allow: GET, HEAD\r\n");
        log_request(remote_ip, "denied", 405);
        return;
    }
    const bool head_only = (method == "HEAD");

    // Strip query string if present
    size_t qpos = target.find('?');
    std::string path = (qpos == std::string::npos) ? target : target.substr(0, qpos);

    // Route: /<token>, /<token>/, /<token>/health
    if (path.empty() || path[0] != '/') {
        bool just_blocked = record_failed_auth(remote_ip);
        send_http_response(io, 404, nullptr, "", head_only);
        log_request(remote_ip, just_blocked ? "blocked" : "denied", 404);
        return;
    }

    size_t next_slash = path.find('/', 1);
    std::string provided_token = (next_slash == std::string::npos)
        ? path.substr(1)
        : path.substr(1, next_slash - 1);
    std::string rest = (next_slash == std::string::npos)
        ? std::string()
        : path.substr(next_slash);

    if (!constant_time_equals(provided_token, token_)) {
        bool just_blocked = record_failed_auth(remote_ip);
        send_http_response(io, 404, nullptr, "", head_only);
        log_request(remote_ip, just_blocked ? "blocked" : "denied", 404);
        return;
    }

    if (rest == "/health") {
        std::string body = std::string("{\"ok\":true,\"platform\":\"windows\",\"version\":\"") +
                           kServerVersion + "\"}";
        send_http_response(io, 200, "application/json", body, head_only);
        log_request(remote_ip, "health", 200);
        return;
    }

    if (rest.empty() || rest == "/") {
        if (!check_rate_limit()) {
            send_http_response(io, 429, nullptr, "", head_only);
            log_request(remote_ip, "anisette", 429);
            return;
        }

        AnisetteData data;
        std::string err;
        if (!provider_.fetch(data, err) ||
            data.md.empty() || data.md_m.empty() ||
            data.device_id.empty() || data.md_lu.empty() || data.client_info.empty()) {
            if (err.empty()) err = "anisette computation failed";
            std::string body = "{\"error\":\"" + json_escape(err) + "\"}";
            send_http_response(io, 500, "application/json", body, head_only);
            log_request(remote_ip, "anisette", 500);
            return;
        }

        std::string body = AnisetteProvider::to_json(data);
        send_http_response(io, 200, "application/json", body, head_only);
        log_request(remote_ip, "anisette", 200);
        return;
    }

    bool just_blocked = record_failed_auth(remote_ip);
    send_http_response(io, 404, nullptr, "", head_only);
    log_request(remote_ip, just_blocked ? "blocked" : "denied", 404);
}

} // namespace anisette
