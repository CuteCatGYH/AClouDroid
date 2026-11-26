#pragma once

#include <chrono>
#include <cstdint>
#include <string>
#include <vector>

namespace anisette {

struct CertInfo {
    std::vector<std::string> dns_names;
    bool expired = false;
    bool not_yet_valid = false;
    std::string not_after_utc;   // YYYY-MM-DD
    std::string not_before_utc;  // YYYY-MM-DD
};

class TlsContext {
public:
    TlsContext();
    ~TlsContext();

    TlsContext(const TlsContext&) = delete;
    TlsContext& operator=(const TlsContext&) = delete;

    // Loads a PKCS#12 (.pfx) file and initializes inbound Schannel TLS 1.2+ credentials.
    // has_password distinguishes explicit --tls-pfx-password "" from omitted flag.
    bool load_pfx(
        const std::wstring& pfx_path,
        const std::wstring& password,
        bool has_password,
        std::string& err);

    bool is_loaded() const;
    const CertInfo& cert_info() const { return cert_info_; }

    // Internal opaque handle accessor for TlsConnection.
    void* native_cred_handle() const;

private:
    struct Impl;
    Impl* impl_ = nullptr;
    CertInfo cert_info_;
};

class TlsConnection {
public:
    TlsConnection(uintptr_t sock, const TlsContext& ctx);
    ~TlsConnection();

    TlsConnection(const TlsConnection&) = delete;
    TlsConnection& operator=(const TlsConnection&) = delete;

    // Runs server-side TLS handshake until deadline. Returns false on error/timeout/plain-HTTP.
    bool handshake(std::chrono::steady_clock::time_point deadline);

    // Reads decrypted bytes up to max_len before deadline.
    // Returns >0 bytes read, 0 on clean EOF, -1 on error, -2 on timeout.
    int read_some(char* dst, size_t max_len, std::chrono::steady_clock::time_point deadline);

    // Encrypts and sends all bytes.
    bool write_all(const char* data, size_t len);

    // Sends TLS close_notify and releases security context.
    void shutdown();

private:
    struct Impl;
    Impl* impl_ = nullptr;
};

} // namespace anisette
