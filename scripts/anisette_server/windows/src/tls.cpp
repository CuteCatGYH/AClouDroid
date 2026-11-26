#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define SECURITY_WIN32

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <wincrypt.h>
#include <security.h>
#include <schannel.h>

#include "tls.hpp"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <vector>

#ifndef PKCS12_ALLOW_OVERWRITE_KEY
#define PKCS12_ALLOW_OVERWRITE_KEY 0x00004000
#endif

namespace anisette {

namespace {

constexpr size_t kMaxPfxFileBytes = 16u * 1024u * 1024u;
constexpr size_t kMaxTlsRecordBuf = 65536u;

std::string wide_to_utf8(const std::wstring& w) {
    if (w.empty()) return "";
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    if (n <= 0) return "";
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

std::string format_filetime_date_utc(const FILETIME& ft) {
    SYSTEMTIME st{};
    if (!FileTimeToSystemTime(&ft, &st)) {
        return "";
    }
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%04u-%02u-%02u", st.wYear, st.wMonth, st.wDay);
    return std::string(buf);
}

bool cert_has_private_key(PCCERT_CONTEXT cert) {
    DWORD cb = 0;
    if (CertGetCertificateContextProperty(cert, CERT_NCRYPT_KEY_HANDLE_PROP_ID, nullptr, &cb) && cb > 0) {
        return true;
    }
    cb = 0;
    if (CertGetCertificateContextProperty(cert, CERT_KEY_PROV_INFO_PROP_ID, nullptr, &cb) && cb > 0) {
        return true;
    }
    cb = 0;
    if (CertGetCertificateContextProperty(cert, CERT_KEY_CONTEXT_PROP_ID, nullptr, &cb) && cb > 0) {
        return true;
    }
    return false;
}

void extract_cert_info(PCCERT_CONTEXT cert, CertInfo& info) {
    info = CertInfo{};
    if (!cert || !cert->pCertInfo) return;

    // Expiry check
    FILETIME now{};
    GetSystemTimeAsFileTime(&now);
    info.expired = (CompareFileTime(&now, &cert->pCertInfo->NotAfter) > 0);
    info.not_yet_valid = (CompareFileTime(&now, &cert->pCertInfo->NotBefore) < 0);
    info.not_after_utc = format_filetime_date_utc(cert->pCertInfo->NotAfter);
    info.not_before_utc = format_filetime_date_utc(cert->pCertInfo->NotBefore);

    auto add_dns = [&](const std::string& name) {
        if (name.empty()) return;
        if (std::find(info.dns_names.begin(), info.dns_names.end(), name) == info.dns_names.end()) {
            info.dns_names.push_back(name);
        }
    };

    // Extract DNS names from Subject Alternative Name (SAN) extension
    PCERT_EXTENSION san_ext = CertFindExtension(
        szOID_SUBJECT_ALT_NAME2,
        cert->pCertInfo->cExtension,
        cert->pCertInfo->rgExtension);
    if (san_ext) {
        CERT_ALT_NAME_INFO* alt_info = nullptr;
        DWORD cb = 0;
        if (CryptDecodeObjectEx(
                X509_ASN_ENCODING,
                X509_ALTERNATE_NAME,
                san_ext->Value.pbData,
                san_ext->Value.cbData,
                CRYPT_DECODE_ALLOC_FLAG,
                nullptr,
                &alt_info,
                &cb) &&
            alt_info) {
            for (DWORD i = 0; i < alt_info->cAltEntry; ++i) {
                const CERT_ALT_NAME_ENTRY& e = alt_info->rgAltEntry[i];
                if (e.dwAltNameChoice == CERT_ALT_NAME_DNS_NAME && e.pwszDNSName) {
                    add_dns(wide_to_utf8(e.pwszDNSName));
                }
            }
            LocalFree(alt_info);
        }
    }

    // Fallback to Subject Common Name (CN) if SAN has no DNS names
    if (info.dns_names.empty()) {
        DWORD len = CertGetNameStringW(
            cert,
            CERT_NAME_ATTR_TYPE,
            0,
            const_cast<char*>(szOID_COMMON_NAME),
            nullptr,
            0);
        if (len > 1) {
            std::wstring cn(len, L'\0');
            if (CertGetNameStringW(
                    cert,
                    CERT_NAME_ATTR_TYPE,
                    0,
                    const_cast<char*>(szOID_COMMON_NAME),
                    cn.data(),
                    len) > 1) {
                while (!cn.empty() && cn.back() == L'\0') cn.pop_back();
                add_dns(wide_to_utf8(cn));
            }
        }
    }
}

bool raw_send_all(SOCKET s, const char* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        int n = send(s, data + sent, static_cast<int>(len - sent), 0);
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

// Returns >0 bytes received, 0 on EOF, -1 on error, -2 on timeout.
int raw_recv_with_deadline(
    SOCKET s,
    char* buf,
    int max_len,
    std::chrono::steady_clock::time_point deadline) {
    auto now = std::chrono::steady_clock::now();
    if (now >= deadline) return -2;
    auto rem_ms = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
    if (rem_ms <= 0) rem_ms = 1;

    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(s, &rfds);
    timeval tv{};
    tv.tv_sec = static_cast<long>(rem_ms / 1000);
    tv.tv_usec = static_cast<long>((rem_ms % 1000) * 1000);

    int sel = select(0, &rfds, nullptr, nullptr, &tv);
    if (sel == 0) return -2;
    if (sel < 0) return -1;

    int n = recv(s, buf, max_len, 0);
    if (n == 0) return 0;
    if (n < 0) return -1;
    return n;
}

} // namespace

struct TlsContext::Impl {
    HCERTSTORE store = nullptr;
    PCCERT_CONTEXT cert = nullptr;
    CredHandle cred{};
    bool has_cred = false;

    ~Impl() {
        reset();
    }

    void reset() {
        if (has_cred) {
            FreeCredentialsHandle(&cred);
            SecInvalidateHandle(&cred);
            has_cred = false;
        }
        if (cert) {
            CertFreeCertificateContext(cert);
            cert = nullptr;
        }
        if (store) {
            CertCloseStore(store, 0);
            store = nullptr;
        }
    }
};

TlsContext::TlsContext() : impl_(new Impl()) {
    SecInvalidateHandle(&impl_->cred);
}

TlsContext::~TlsContext() {
    delete impl_;
}

bool TlsContext::is_loaded() const {
    return impl_ && impl_->has_cred;
}

void* TlsContext::native_cred_handle() const {
    return (impl_ && impl_->has_cred) ? static_cast<void*>(&impl_->cred) : nullptr;
}

bool TlsContext::load_pfx(
    const std::wstring& pfx_path,
    const std::wstring& password,
    bool has_password,
    std::string& err) {
    impl_->reset();
    cert_info_ = CertInfo{};

    HANDLE h = CreateFileW(
        pfx_path.c_str(),
        GENERIC_READ,
        FILE_SHARE_READ,
        nullptr,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        err = "failed to open TLS PFX file (winerr " + std::to_string(GetLastError()) + ")";
        return false;
    }

    LARGE_INTEGER sz{};
    if (!GetFileSizeEx(h, &sz) || sz.QuadPart <= 0 ||
        static_cast<uint64_t>(sz.QuadPart) > kMaxPfxFileBytes) {
        CloseHandle(h);
        err = "TLS PFX file is empty or too large";
        return false;
    }

    std::vector<BYTE> raw(static_cast<size_t>(sz.QuadPart));
    size_t total = 0;
    while (total < raw.size()) {
        DWORD nr = 0;
        if (!ReadFile(h, raw.data() + total, static_cast<DWORD>(raw.size() - total), &nr, nullptr)) {
            DWORD e = GetLastError();
            CloseHandle(h);
            err = "failed to read TLS PFX file (winerr " + std::to_string(e) + ")";
            return false;
        }
        if (nr == 0) break;
        total += nr;
    }
    CloseHandle(h);
    raw.resize(total);

    CRYPT_DATA_BLOB blob{};
    blob.cbData = static_cast<DWORD>(raw.size());
    blob.pbData = raw.data();

    if (!PFXIsPFXBlob(&blob)) {
        err = "file is not a valid PFX/PKCS#12 archive";
        return false;
    }

    // Schannel (lsass.exe) requires the private key to reside in a persisted keyset
    // (CRYPT_USER_KEYSET); ephemeral PKCS12_NO_PERSIST_KEY keys fail with SEC_E_NO_CREDENTIALS.
    auto try_import = [&](LPCWSTR pw) -> HCERTSTORE {
        HCERTSTORE st = PFXImportCertStore(&blob, pw, CRYPT_USER_KEYSET | PKCS12_ALLOW_OVERWRITE_KEY);
        if (!st) {
            DWORD e = GetLastError();
            if (e != ERROR_INVALID_PASSWORD) {
                st = PFXImportCertStore(&blob, pw, CRYPT_USER_KEYSET);
            }
        }
        return st;
    };

    HCERTSTORE store = nullptr;
    if (has_password) {
        store = try_import(password.c_str());
    } else {
        store = try_import(L"");
        if (!store) {
            store = try_import(nullptr);
        }
    }

    if (!store) {
        DWORD e = GetLastError();
        if (e == ERROR_INVALID_PASSWORD) {
            err = "failed to decrypt TLS PFX (invalid password; use --tls-pfx-password)";
        } else {
            err = "PFXImportCertStore failed (winerr " + std::to_string(e) + ")";
        }
        return false;
    }

    // Locate the leaf certificate that has a private key.
    PCCERT_CONTEXT chosen = nullptr;
    PCCERT_CONTEXT prev = nullptr;
    while ((prev = CertEnumCertificatesInStore(store, prev)) != nullptr) {
        if (cert_has_private_key(prev)) {
            chosen = CertDuplicateCertificateContext(prev);
            CertFreeCertificateContext(prev);
            break;
        }
    }

    if (!chosen) {
        CertCloseStore(store, 0);
        err = "no certificate with private key found in PFX";
        return false;
    }

    extract_cert_info(chosen, cert_info_);

    // Acquire inbound Schannel credentials for TLS 1.2+ (disallow SSL2/3/TLS1.0/1.1).
    SECURITY_STATUS ss = SEC_E_INTERNAL_ERROR;
    TimeStamp expiry{};

#ifdef SP_PROT_TLS1_3_SERVER
    {
        SCHANNEL_CRED cred{};
        cred.dwVersion = SCHANNEL_CRED_VERSION;
        cred.cCreds = 1;
        cred.paCred = &chosen;
        cred.grbitEnabledProtocols = SP_PROT_TLS1_2_SERVER | SP_PROT_TLS1_3_SERVER;
        cred.dwFlags = SCH_CRED_NO_SYSTEM_MAPPER | SCH_USE_STRONG_CRYPTO;

        ss = AcquireCredentialsHandleW(
            nullptr,
            const_cast<LPWSTR>(UNISP_NAME_W),
            SECPKG_CRED_INBOUND,
            nullptr,
            &cred,
            nullptr,
            nullptr,
            &impl_->cred,
            &expiry);
    }
#endif

    if (ss != SEC_E_OK) {
        SCHANNEL_CRED cred{};
        cred.dwVersion = SCHANNEL_CRED_VERSION;
        cred.cCreds = 1;
        cred.paCred = &chosen;
        cred.grbitEnabledProtocols = SP_PROT_TLS1_2_SERVER;
        cred.dwFlags = SCH_CRED_NO_SYSTEM_MAPPER | SCH_USE_STRONG_CRYPTO;

        ss = AcquireCredentialsHandleW(
            nullptr,
            const_cast<LPWSTR>(UNISP_NAME_W),
            SECPKG_CRED_INBOUND,
            nullptr,
            &cred,
            nullptr,
            nullptr,
            &impl_->cred,
            &expiry);
    }

    if (ss != SEC_E_OK) {
        CertFreeCertificateContext(chosen);
        CertCloseStore(store, 0);
        char hex[32];
        std::snprintf(hex, sizeof(hex), "0x%08X", static_cast<unsigned int>(ss));
        err = std::string("AcquireCredentialsHandle failed (") + hex + ")";
        return false;
    }

    impl_->store = store;
    impl_->cert = chosen;
    impl_->has_cred = true;
    return true;
}

struct TlsConnection::Impl {
    SOCKET sock = INVALID_SOCKET;
    CredHandle* cred = nullptr;
    CtxtHandle ctxt{};
    bool has_ctxt = false;
    SecPkgContext_StreamSizes sizes{};
    std::vector<uint8_t> enc_buf;
    std::vector<uint8_t> plain_buf;
};

TlsConnection::TlsConnection(uintptr_t sock, const TlsContext& ctx) : impl_(new Impl()) {
    impl_->sock = static_cast<SOCKET>(sock);
    impl_->cred = static_cast<CredHandle*>(ctx.native_cred_handle());
    SecInvalidateHandle(&impl_->ctxt);
}

TlsConnection::~TlsConnection() {
    if (impl_) {
        if (impl_->has_ctxt) {
            DeleteSecurityContext(&impl_->ctxt);
            SecInvalidateHandle(&impl_->ctxt);
            impl_->has_ctxt = false;
        }
        delete impl_;
        impl_ = nullptr;
    }
}

bool TlsConnection::handshake(std::chrono::steady_clock::time_point deadline) {
    if (!impl_ || !impl_->cred || impl_->sock == INVALID_SOCKET) {
        return false;
    }

    const ULONG req_flags =
        ASC_REQ_SEQUENCE_DETECT |
        ASC_REQ_REPLAY_DETECT |
        ASC_REQ_CONFIDENTIALITY |
        ASC_REQ_EXTENDED_ERROR |
        ASC_REQ_ALLOCATE_MEMORY |
        ASC_REQ_STREAM;

    bool need_more_data = true;
    char recv_chunk[4096];

    for (;;) {
        if (need_more_data) {
            if (impl_->enc_buf.size() >= kMaxTlsRecordBuf) {
                return false;
            }
            int to_read = static_cast<int>(std::min<size_t>(
                sizeof(recv_chunk), kMaxTlsRecordBuf - impl_->enc_buf.size()));
            int n = raw_recv_with_deadline(impl_->sock, recv_chunk, to_read, deadline);
            if (n <= 0) {
                return false;
            }
            impl_->enc_buf.insert(
                impl_->enc_buf.end(),
                reinterpret_cast<const uint8_t*>(recv_chunk),
                reinterpret_cast<const uint8_t*>(recv_chunk) + n);
            need_more_data = false;
        }

        // Reject plain HTTP or non-TLS-handshake bytes immediately on first record
        // (TLS 1.2+ ClientHello starts with record type 0x16 = Handshake).
        if (!impl_->has_ctxt && !impl_->enc_buf.empty() && impl_->enc_buf[0] != 0x16) {
            return false;
        }

        SecBuffer in_bufs[2]{};
        in_bufs[0].BufferType = SECBUFFER_TOKEN;
        in_bufs[0].cbBuffer = static_cast<unsigned long>(impl_->enc_buf.size());
        in_bufs[0].pvBuffer = impl_->enc_buf.data();
        in_bufs[1].BufferType = SECBUFFER_EMPTY;
        in_bufs[1].cbBuffer = 0;
        in_bufs[1].pvBuffer = nullptr;

        SecBufferDesc in_desc{};
        in_desc.ulVersion = SECBUFFER_VERSION;
        in_desc.cBuffers = 2;
        in_desc.pBuffers = in_bufs;

        SecBuffer out_bufs[1]{};
        out_bufs[0].BufferType = SECBUFFER_TOKEN;
        out_bufs[0].cbBuffer = 0;
        out_bufs[0].pvBuffer = nullptr;

        SecBufferDesc out_desc{};
        out_desc.ulVersion = SECBUFFER_VERSION;
        out_desc.cBuffers = 1;
        out_desc.pBuffers = out_bufs;

        ULONG out_flags = 0;
        TimeStamp ts{};

        SECURITY_STATUS ss = AcceptSecurityContext(
            impl_->cred,
            impl_->has_ctxt ? &impl_->ctxt : nullptr,
            &in_desc,
            req_flags,
            0,
            &impl_->ctxt,
            &out_desc,
            &out_flags,
            &ts);

        if (ss == SEC_E_OK || ss == SEC_I_CONTINUE_NEEDED) {
            impl_->has_ctxt = true;
        }

        if (out_bufs[0].cbBuffer > 0 && out_bufs[0].pvBuffer != nullptr) {
            bool sent = raw_send_all(
                impl_->sock,
                static_cast<const char*>(out_bufs[0].pvBuffer),
                out_bufs[0].cbBuffer);
            FreeContextBuffer(out_bufs[0].pvBuffer);
            if (!sent) {
                return false;
            }
        }

        if (ss == SEC_E_INCOMPLETE_MESSAGE) {
            need_more_data = true;
            continue;
        }

        if (ss == SEC_I_CONTINUE_NEEDED || ss == SEC_E_OK) {
            if (in_bufs[1].BufferType == SECBUFFER_EXTRA && in_bufs[1].cbBuffer > 0 &&
                in_bufs[1].cbBuffer <= impl_->enc_buf.size()) {
                size_t extra = in_bufs[1].cbBuffer;
                size_t consumed = impl_->enc_buf.size() - extra;
                std::memmove(impl_->enc_buf.data(), impl_->enc_buf.data() + consumed, extra);
                impl_->enc_buf.resize(extra);
            } else {
                impl_->enc_buf.clear();
            }

            if (ss == SEC_E_OK) {
                SECURITY_STATUS qs = QueryContextAttributesW(
                    &impl_->ctxt, SECPKG_ATTR_STREAM_SIZES, &impl_->sizes);
                return qs == SEC_E_OK && impl_->sizes.cbMaximumMessage > 0;
            }

            if (impl_->enc_buf.empty()) {
                need_more_data = true;
            }
            continue;
        }

        // Any other status (SEC_E_INVALID_TOKEN, SEC_E_UNSUPPORTED_FUNCTION, etc.)
        return false;
    }
}

int TlsConnection::read_some(
    char* dst,
    size_t max_len,
    std::chrono::steady_clock::time_point deadline) {
    if (!impl_ || !impl_->has_ctxt || max_len == 0) return -1;

    auto drain_plain = [&]() -> int {
        size_t take = std::min(max_len, impl_->plain_buf.size());
        std::memcpy(dst, impl_->plain_buf.data(), take);
        impl_->plain_buf.erase(impl_->plain_buf.begin(), impl_->plain_buf.begin() + static_cast<ptrdiff_t>(take));
        return static_cast<int>(take);
    };

    if (!impl_->plain_buf.empty()) {
        return drain_plain();
    }

    char recv_chunk[4096];

    for (;;) {
        if (!impl_->enc_buf.empty()) {
            SecBuffer bufs[4]{};
            bufs[0].BufferType = SECBUFFER_DATA;
            bufs[0].cbBuffer = static_cast<unsigned long>(impl_->enc_buf.size());
            bufs[0].pvBuffer = impl_->enc_buf.data();
            bufs[1].BufferType = SECBUFFER_EMPTY;
            bufs[2].BufferType = SECBUFFER_EMPTY;
            bufs[3].BufferType = SECBUFFER_EMPTY;

            SecBufferDesc desc{};
            desc.ulVersion = SECBUFFER_VERSION;
            desc.cBuffers = 4;
            desc.pBuffers = bufs;

            SECURITY_STATUS ss = DecryptMessage(&impl_->ctxt, &desc, 0, nullptr);
            if (ss == SEC_E_OK) {
                SecBuffer* data_buf = nullptr;
                SecBuffer* extra_buf = nullptr;
                for (int i = 0; i < 4; ++i) {
                    if (bufs[i].BufferType == SECBUFFER_DATA && bufs[i].cbBuffer > 0) {
                        data_buf = &bufs[i];
                    } else if (bufs[i].BufferType == SECBUFFER_EXTRA && bufs[i].cbBuffer > 0) {
                        extra_buf = &bufs[i];
                    }
                }
                if (data_buf) {
                    const auto* p = static_cast<const uint8_t*>(data_buf->pvBuffer);
                    impl_->plain_buf.insert(impl_->plain_buf.end(), p, p + data_buf->cbBuffer);
                }
                if (extra_buf && extra_buf->cbBuffer <= impl_->enc_buf.size()) {
                    std::memmove(impl_->enc_buf.data(), extra_buf->pvBuffer, extra_buf->cbBuffer);
                    impl_->enc_buf.resize(extra_buf->cbBuffer);
                } else {
                    impl_->enc_buf.clear();
                }
                if (!impl_->plain_buf.empty()) {
                    return drain_plain();
                }
                continue;
            }
            if (ss == SEC_I_CONTEXT_EXPIRED) {
                return 0;
            }
            if (ss != SEC_E_INCOMPLETE_MESSAGE) {
                return -1;
            }
        }

        if (impl_->enc_buf.size() >= kMaxTlsRecordBuf) {
            return -1;
        }
        int to_read = static_cast<int>(std::min<size_t>(
            sizeof(recv_chunk), kMaxTlsRecordBuf - impl_->enc_buf.size()));
        int n = raw_recv_with_deadline(impl_->sock, recv_chunk, to_read, deadline);
        if (n == -2) return -2;
        if (n < 0) return -1;
        if (n == 0) {
            return impl_->enc_buf.empty() ? 0 : -1;
        }
        impl_->enc_buf.insert(
            impl_->enc_buf.end(),
            reinterpret_cast<const uint8_t*>(recv_chunk),
            reinterpret_cast<const uint8_t*>(recv_chunk) + n);
    }
}

bool TlsConnection::write_all(const char* data, size_t len) {
    if (!impl_ || !impl_->has_ctxt) return false;

    const size_t hdr_sz = impl_->sizes.cbHeader;
    const size_t trl_sz = impl_->sizes.cbTrailer;
    const size_t max_msg = impl_->sizes.cbMaximumMessage;
    std::vector<uint8_t> rec_buf(hdr_sz + max_msg + trl_sz);

    size_t offset = 0;
    while (offset < len) {
        size_t chunk = std::min(len - offset, max_msg);
        std::memcpy(rec_buf.data() + hdr_sz, data + offset, chunk);

        SecBuffer bufs[4]{};
        bufs[0].BufferType = SECBUFFER_STREAM_HEADER;
        bufs[0].cbBuffer = static_cast<unsigned long>(hdr_sz);
        bufs[0].pvBuffer = rec_buf.data();

        bufs[1].BufferType = SECBUFFER_DATA;
        bufs[1].cbBuffer = static_cast<unsigned long>(chunk);
        bufs[1].pvBuffer = rec_buf.data() + hdr_sz;

        bufs[2].BufferType = SECBUFFER_STREAM_TRAILER;
        bufs[2].cbBuffer = static_cast<unsigned long>(trl_sz);
        bufs[2].pvBuffer = rec_buf.data() + hdr_sz + chunk;

        bufs[3].BufferType = SECBUFFER_EMPTY;

        SecBufferDesc desc{};
        desc.ulVersion = SECBUFFER_VERSION;
        desc.cBuffers = 4;
        desc.pBuffers = bufs;

        SECURITY_STATUS ss = EncryptMessage(&impl_->ctxt, 0, &desc, 0);
        if (ss != SEC_E_OK) {
            return false;
        }

        size_t total = static_cast<size_t>(bufs[0].cbBuffer) +
                       static_cast<size_t>(bufs[1].cbBuffer) +
                       static_cast<size_t>(bufs[2].cbBuffer);
        if (!raw_send_all(impl_->sock, reinterpret_cast<const char*>(rec_buf.data()), total)) {
            return false;
        }
        offset += chunk;
    }
    return true;
}

void TlsConnection::shutdown() {
    if (!impl_ || !impl_->has_ctxt) return;

    DWORD shut_type = SCHANNEL_SHUTDOWN;
    SecBuffer in_buf{};
    in_buf.BufferType = SECBUFFER_TOKEN;
    in_buf.cbBuffer = sizeof(shut_type);
    in_buf.pvBuffer = &shut_type;

    SecBufferDesc in_desc{};
    in_desc.ulVersion = SECBUFFER_VERSION;
    in_desc.cBuffers = 1;
    in_desc.pBuffers = &in_buf;

    if (ApplyControlToken(&impl_->ctxt, &in_desc) == SEC_E_OK) {
        SecBuffer out_buf{};
        out_buf.BufferType = SECBUFFER_TOKEN;
        out_buf.cbBuffer = 0;
        out_buf.pvBuffer = nullptr;

        SecBufferDesc out_desc{};
        out_desc.ulVersion = SECBUFFER_VERSION;
        out_desc.cBuffers = 1;
        out_desc.pBuffers = &out_buf;

        ULONG out_flags = 0;
        TimeStamp ts{};
        const ULONG req_flags =
            ASC_REQ_SEQUENCE_DETECT |
            ASC_REQ_REPLAY_DETECT |
            ASC_REQ_CONFIDENTIALITY |
            ASC_REQ_EXTENDED_ERROR |
            ASC_REQ_ALLOCATE_MEMORY |
            ASC_REQ_STREAM;

        AcceptSecurityContext(
            impl_->cred,
            &impl_->ctxt,
            nullptr,
            req_flags,
            0,
            &impl_->ctxt,
            &out_desc,
            &out_flags,
            &ts);

        if (out_buf.cbBuffer > 0 && out_buf.pvBuffer != nullptr) {
            raw_send_all(impl_->sock, static_cast<const char*>(out_buf.pvBuffer), out_buf.cbBuffer);
            FreeContextBuffer(out_buf.pvBuffer);
        }
    }

    DeleteSecurityContext(&impl_->ctxt);
    SecInvalidateHandle(&impl_->ctxt);
    impl_->has_ctxt = false;
}

} // namespace anisette
