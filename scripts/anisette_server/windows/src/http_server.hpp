#pragma once

#include "anisette.hpp"
#include "tls.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace anisette {

constexpr const char* kServerVersion = "1.0.0";

struct ListenEndpoint {
    std::string host;
    uint16_t port = 6969;
};

// Parses "host:port" or "[ipv6]:port".
bool parse_listen_addr(const std::string& input, ListenEndpoint& out);

// Returns true if host is a loopback address (127.x.x.x, ::1, ::ffff:127.x.x.x, localhost).
bool is_loopback_host(const std::string& host);

// Enumerates all non-loopback IPv4 addresses on active network interfaces.
std::vector<std::string> get_non_loopback_ipv4_addresses();

class HttpServer {
public:
    HttpServer(
        ListenEndpoint endpoint,
        std::string token,
        AnisetteProvider& provider,
        const TlsContext* tls = nullptr);
    ~HttpServer();

    HttpServer(const HttpServer&) = delete;
    HttpServer& operator=(const HttpServer&) = delete;

    // Binds and starts listening. Returns false on error.
    bool bind_and_listen(std::string& err);

    // Blocks in accept loop until stop() is called.
    void serve();

    // Signals accept loop to exit.
    void stop();

private:
    struct IpGuardState {
        std::deque<std::chrono::steady_clock::time_point> failures;
        std::chrono::steady_clock::time_point blocked_until{};
        std::chrono::steady_clock::time_point last_seen{};
    };

    void handle_client(uintptr_t client_sock, std::string remote_ip);
    bool check_rate_limit();
    bool is_ip_blocked(const std::string& remote_ip);
    bool record_failed_auth(const std::string& remote_ip);
    void log_request(const std::string& remote_ip, const char* path_class, int status);

    ListenEndpoint endpoint_;
    std::string token_;
    AnisetteProvider& provider_;
    const TlsContext* tls_ = nullptr;

    uintptr_t listen_sock_ = static_cast<uintptr_t>(~0ULL);
    std::atomic<bool> running_{false};
    std::atomic<int> active_conns_{0};

    std::mutex rate_mu_;
    std::deque<std::chrono::steady_clock::time_point> anisette_timestamps_;

    std::mutex bf_mu_;
    std::unordered_map<std::string, IpGuardState> bf_map_;

    std::mutex log_mu_;
};

} // namespace anisette
