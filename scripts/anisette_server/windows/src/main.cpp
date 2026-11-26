#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include "anisette.hpp"
#include "config.hpp"
#include "http_server.hpp"
#include "tls.hpp"

#include <algorithm>
#include <cstdio>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

namespace {

anisette::HttpServer* g_server = nullptr;

BOOL WINAPI console_ctrl_handler(DWORD ctrl_type) {
    switch (ctrl_type) {
        case CTRL_C_EVENT:
        case CTRL_BREAK_EVENT:
        case CTRL_CLOSE_EVENT:
        case CTRL_SHUTDOWN_EVENT:
            if (g_server) {
                g_server->stop();
            }
            return TRUE;
        default:
            return FALSE;
    }
}

std::wstring utf8_to_wide(const std::string& s) {
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) return L"";
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

void print_help() {
    std::printf(
        "AClouDroid Anisette Server %s (windows)\n\n"
        "Usage:\n"
        "  anisette-server.exe [options]\n\n"
        "Options:\n"
        "  --listen host:port          Address to listen on (default: 127.0.0.1:6969)\n"
        "  --token value               Override auth token for this run\n"
        "  --new-token                 Regenerate and save a new random token\n"
        "  --show-url                  Print banner URLs and exit\n"
        "  --icloud-dir path           Override iCloud DLL directory\n"
        "  --tls-pfx file              Enable HTTPS (TLS 1.2+) using PKCS#12 (.pfx) cert\n"
        "  --tls-pfx-password pw       Password for --tls-pfx archive\n"
        "  --version                   Print version and exit\n"
        "  --help                      Show this help message\n\n"
        "Environment:\n"
        "  ACLOUDDROID_ANISETTE_HOME   Override config directory\n",
        anisette::kServerVersion);
}

void print_banner(
    const anisette::ListenEndpoint& ep,
    const std::string& token,
    const anisette::TlsContext* tls,
    bool show_listen_line) {
    const bool use_tls = (tls && tls->is_loaded());
    const bool loopback = anisette::is_loopback_host(ep.host);

    std::printf("AClouDroid Anisette Server %s (windows)\n", anisette::kServerVersion);
    if (show_listen_line) {
        std::printf("Listening on %s:%u%s\n",
                    ep.host.c_str(),
                    static_cast<unsigned>(ep.port),
                    use_tls ? " (TLS)" : "");
    }

    if (use_tls) {
        const auto& info = tls->cert_info();
        if (info.expired) {
            std::printf("WARNING: TLS certificate expired on %s!\n",
                        info.not_after_utc.empty() ? "an earlier date" : info.not_after_utc.c_str());
        } else if (info.not_yet_valid) {
            std::printf("WARNING: TLS certificate is not valid until %s!\n",
                        info.not_before_utc.empty() ? "a later date" : info.not_before_utc.c_str());
        }

        std::printf("Paste one of these into AClouDroid -> 设置 -> 大文件上传 -> 设备指纹地址:\n");
        if (!info.dns_names.empty()) {
            for (const auto& dns : info.dns_names) {
                std::printf("  https://%s:%u/%s/\n",
                            dns.c_str(), static_cast<unsigned>(ep.port), token.c_str());
            }
        } else {
            if (ep.host.find(':') != std::string::npos) {
                std::printf("  https://[%s]:%u/%s/\n",
                            ep.host.c_str(), static_cast<unsigned>(ep.port), token.c_str());
            } else {
                std::printf("  https://%s:%u/%s/\n",
                            ep.host.c_str(), static_cast<unsigned>(ep.port), token.c_str());
            }
        }
        if (loopback) {
            std::printf("(loopback only: use --listen 0.0.0.0:%u for LAN, or put a tunnel in front)\n",
                        static_cast<unsigned>(ep.port));
        }
    } else if (loopback) {
        std::printf("Paste one of these into AClouDroid -> 设置 -> 大文件上传 -> 设备指纹地址:\n");
        if (ep.host.find(':') != std::string::npos) {
            std::printf("  http://[%s]:%u/%s/\n",
                        ep.host.c_str(), static_cast<unsigned>(ep.port), token.c_str());
        } else {
            std::printf("  http://%s:%u/%s/\n",
                        ep.host.c_str(), static_cast<unsigned>(ep.port), token.c_str());
        }
        std::printf("(loopback only: use --listen 0.0.0.0:%u for LAN, or put a tunnel in front)\n",
                    static_cast<unsigned>(ep.port));
    } else {
        std::printf(
            "WARNING: plain HTTP on a non-loopback address (%s:%u). "
            "Fine on home Wi-Fi or Tailscale; for a public IP use TLS or an HTTPS tunnel.\n",
            ep.host.c_str(), static_cast<unsigned>(ep.port));
        std::printf("Paste one of these into AClouDroid -> 设置 -> 大文件上传 -> 设备指纹地址:\n");

        std::vector<std::string> hosts = anisette::get_non_loopback_ipv4_addresses();
        if (!ep.host.empty() && ep.host != "0.0.0.0" && ep.host != "::" && ep.host != "127.0.0.1" &&
            ep.host != "localhost") {
            if (std::find(hosts.begin(), hosts.end(), ep.host) == hosts.end()) {
                hosts.insert(hosts.begin(), ep.host);
            }
        }
        if (std::find(hosts.begin(), hosts.end(), "127.0.0.1") == hosts.end()) {
            hosts.push_back("127.0.0.1");
        }

        for (const auto& h : hosts) {
            if (h.find(':') != std::string::npos) {
                std::printf("  http://[%s]:%u/%s/\n", h.c_str(), static_cast<unsigned>(ep.port), token.c_str());
            } else {
                std::printf("  http://%s:%u/%s/\n", h.c_str(), static_cast<unsigned>(ep.port), token.c_str());
            }
        }
    }
    std::fflush(stdout);
}

} // namespace

int main(int argc, char** argv) {
    SetConsoleOutputCP(CP_UTF8);

    std::string listen_arg = "127.0.0.1:6969";
    std::string token_override;
    std::wstring icloud_dir_override;
    std::wstring tls_pfx_path;
    std::wstring tls_pfx_password;
    bool has_tls_password = false;
    bool new_token = false;
    bool show_url = false;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--help" || arg == "-h") {
            print_help();
            return 0;
        }
        if (arg == "--version") {
            std::printf("AClouDroid Anisette Server %s (windows)\n", anisette::kServerVersion);
            return 0;
        }
        if (arg == "--new-token") {
            new_token = true;
            continue;
        }
        if (arg == "--show-url") {
            show_url = true;
            continue;
        }
        if (arg == "--listen") {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "Error: --listen requires an argument\n");
                return 2;
            }
            listen_arg = argv[++i];
            continue;
        }
        if (arg == "--token") {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "Error: --token requires an argument\n");
                return 2;
            }
            token_override = argv[++i];
            continue;
        }
        if (arg == "--icloud-dir") {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "Error: --icloud-dir requires an argument\n");
                return 2;
            }
            icloud_dir_override = utf8_to_wide(argv[++i]);
            continue;
        }
        if (arg == "--tls-pfx") {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "Error: --tls-pfx requires an argument\n");
                return 2;
            }
            tls_pfx_path = utf8_to_wide(argv[++i]);
            continue;
        }
        if (arg == "--tls-pfx-password") {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "Error: --tls-pfx-password requires an argument\n");
                return 2;
            }
            tls_pfx_password = utf8_to_wide(argv[++i]);
            has_tls_password = true;
            continue;
        }
        std::fprintf(stderr, "Error: unknown option '%s' (use --help)\n", arg.c_str());
        return 2;
    }

    if (has_tls_password && tls_pfx_path.empty()) {
        std::fprintf(stderr, "Error: --tls-pfx-password requires --tls-pfx\n");
        return 2;
    }

    anisette::ListenEndpoint ep;
    if (!anisette::parse_listen_addr(listen_arg, ep)) {
        std::fprintf(stderr, "Error: invalid --listen address '%s' (expected host:port)\n",
                     listen_arg.c_str());
        return 2;
    }

    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        std::fprintf(stderr, "Error: WSAStartup failed\n");
        return 1;
    }

    anisette::Config cfg;
    std::string cfg_err;
    if (!anisette::load_or_create_config(cfg, new_token, cfg_err)) {
        std::fprintf(stderr, "Error: %s\n", cfg_err.c_str());
        WSACleanup();
        return 1;
    }

    std::string active_token = token_override.empty() ? cfg.token : token_override;
    if (active_token.empty()) {
        std::fprintf(stderr, "Error: token cannot be empty\n");
        WSACleanup();
        return 1;
    }

    std::unique_ptr<anisette::TlsContext> tls_ctx;
    if (!tls_pfx_path.empty()) {
        tls_ctx = std::make_unique<anisette::TlsContext>();
        std::string tls_err;
        if (!tls_ctx->load_pfx(tls_pfx_path, tls_pfx_password, has_tls_password, tls_err)) {
            std::fprintf(stderr, "Error: %s\n", tls_err.c_str());
            WSACleanup();
            return 1;
        }
    }

    if (show_url) {
        print_banner(ep, active_token, tls_ctx.get(), false);
        WSACleanup();
        return 0;
    }

    // Initialize iCloud provider (dedicated worker thread)
    anisette::AnisetteProvider provider(icloud_dir_override);
    bool dlls_ok = provider.init();

    // Bind listening socket before printing banner so we fail fast on port conflict
    anisette::HttpServer server(ep, active_token, provider, tls_ctx.get());
    std::string bind_err;
    if (!server.bind_and_listen(bind_err)) {
        std::fprintf(stderr, "Error: %s\n", bind_err.c_str());
        WSACleanup();
        return 1;
    }

    print_banner(ep, active_token, tls_ctx.get(), true);

    // Run self-test (compute anisette once) and keep serving regardless
    if (!dlls_ok) {
        std::printf("self-test FAILED: %s — install iCloud for Windows and sign in once.\n",
                    provider.init_error().c_str());
    } else {
        anisette::AnisetteData test_data;
        std::string test_err;
        if (provider.fetch(test_data, test_err)) {
            std::string dev = anisette::AnisetteProvider::client_info_device_part(test_data.client_info);
            std::printf("self-test OK (device %s)\n", dev.c_str());
        } else {
            std::printf("self-test FAILED: %s — install iCloud for Windows and sign in once.\n",
                        test_err.c_str());
        }
    }
    std::fflush(stdout);

    g_server = &server;
    SetConsoleCtrlHandler(console_ctrl_handler, TRUE);

    server.serve();

    g_server = nullptr;
    WSACleanup();
    return 0;
}
