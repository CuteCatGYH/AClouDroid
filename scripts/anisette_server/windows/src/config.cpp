#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <sddl.h>
#include <shlobj.h>

#include "config.hpp"

#include <array>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

namespace anisette {

namespace {

bool csprng_bytes(uint8_t* buf, size_t len) {
    NTSTATUS status = BCryptGenRandom(
        nullptr,
        buf,
        static_cast<ULONG>(len),
        BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    return status == 0;
}

std::string encode_b64_impl(const uint8_t* data, size_t len, const char* alphabet, bool pad) {
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    size_t i = 0;
    while (i + 3 <= len) {
        uint32_t v = (static_cast<uint32_t>(data[i]) << 16) |
                     (static_cast<uint32_t>(data[i + 1]) << 8) |
                     static_cast<uint32_t>(data[i + 2]);
        out.push_back(alphabet[(v >> 18) & 0x3F]);
        out.push_back(alphabet[(v >> 12) & 0x3F]);
        out.push_back(alphabet[(v >> 6) & 0x3F]);
        out.push_back(alphabet[v & 0x3F]);
        i += 3;
    }
    size_t rem = len - i;
    if (rem == 1) {
        uint32_t v = static_cast<uint32_t>(data[i]) << 16;
        out.push_back(alphabet[(v >> 18) & 0x3F]);
        out.push_back(alphabet[(v >> 12) & 0x3F]);
        if (pad) {
            out.push_back('=');
            out.push_back('=');
        }
    } else if (rem == 2) {
        uint32_t v = (static_cast<uint32_t>(data[i]) << 16) |
                     (static_cast<uint32_t>(data[i + 1]) << 8);
        out.push_back(alphabet[(v >> 18) & 0x3F]);
        out.push_back(alphabet[(v >> 12) & 0x3F]);
        out.push_back(alphabet[(v >> 6) & 0x3F]);
        if (pad) {
            out.push_back('=');
        }
    }
    return out;
}

// Get current user SID string (e.g., "S-1-5-21-...") for user-only ACL.
std::wstring get_current_user_sid_string() {
    HANDLE hToken = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &hToken)) {
        return L"";
    }
    DWORD size = 0;
    GetTokenInformation(hToken, TokenUser, nullptr, 0, &size);
    if (size == 0) {
        CloseHandle(hToken);
        return L"";
    }
    std::vector<uint8_t> buf(size);
    if (!GetTokenInformation(hToken, TokenUser, buf.data(), size, &size)) {
        CloseHandle(hToken);
        return L"";
    }
    CloseHandle(hToken);
    auto* tu = reinterpret_cast<TOKEN_USER*>(buf.data());
    LPWSTR sidStr = nullptr;
    if (!ConvertSidToStringSidW(tu->User.Sid, &sidStr) || !sidStr) {
        return L"";
    }
    std::wstring result(sidStr);
    LocalFree(sidStr);
    return result;
}

class UserOnlySecurityAttributes {
public:
    explicit UserOnlySecurityAttributes(bool is_dir) {
        std::wstring sid = get_current_user_sid_string();
        if (sid.empty()) return;
        // Protected DACL granting Full Access exclusively to the current user.
        std::wstring sddl = is_dir
            ? (L"D:P(A;OICI;FA;;;" + sid + L")")
            : (L"D:P(A;;FA;;;" + sid + L")");
        if (ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.c_str(), SDDL_REVISION_1, &sd_, nullptr)) {
            sa_.nLength = sizeof(SECURITY_ATTRIBUTES);
            sa_.lpSecurityDescriptor = sd_;
            sa_.bInheritHandle = FALSE;
            valid_ = true;
        }
    }

    ~UserOnlySecurityAttributes() {
        if (sd_) LocalFree(sd_);
    }

    SECURITY_ATTRIBUTES* get() { return valid_ ? &sa_ : nullptr; }
    PSECURITY_DESCRIPTOR sd() const { return valid_ ? sd_ : nullptr; }

private:
    SECURITY_ATTRIBUTES sa_{};
    PSECURITY_DESCRIPTOR sd_ = nullptr;
    bool valid_ = false;
};

// Minimal JSON string extractor for flat {"key":"value", ...} objects.
bool parse_flat_json_string(const std::string& json, const std::string& key, std::string& out) {
    auto skip_ws = [&](size_t& p) {
        while (p < json.size() && (json[p] == ' ' || json[p] == '\t' || json[p] == '\r' || json[p] == '\n')) {
            ++p;
        }
    };
    auto parse_str = [&](size_t& p, std::string& dst) -> bool {
        if (p >= json.size() || json[p] != '"') return false;
        ++p;
        dst.clear();
        while (p < json.size()) {
            char c = json[p++];
            if (c == '"') return true;
            if (c == '\\') {
                if (p >= json.size()) return false;
                char e = json[p++];
                switch (e) {
                    case '"': dst.push_back('"'); break;
                    case '\\': dst.push_back('\\'); break;
                    case '/': dst.push_back('/'); break;
                    case 'b': dst.push_back('\b'); break;
                    case 'f': dst.push_back('\f'); break;
                    case 'n': dst.push_back('\n'); break;
                    case 'r': dst.push_back('\r'); break;
                    case 't': dst.push_back('\t'); break;
                    default: dst.push_back(e); break;
                }
            } else {
                dst.push_back(c);
            }
        }
        return false;
    };

    size_t p = 0;
    skip_ws(p);
    if (p >= json.size() || json[p] != '{') return false;
    ++p;
    while (p < json.size()) {
        skip_ws(p);
        if (p < json.size() && json[p] == '}') return false;
        std::string k;
        if (!parse_str(p, k)) return false;
        skip_ws(p);
        if (p >= json.size() || json[p] != ':') return false;
        ++p;
        skip_ws(p);
        if (p < json.size() && json[p] == '"') {
            std::string v;
            if (!parse_str(p, v)) return false;
            if (k == key) {
                out = std::move(v);
                return true;
            }
        } else {
            // Skip non-string primitive value
            while (p < json.size() && json[p] != ',' && json[p] != '}') ++p;
        }
        skip_ws(p);
        if (p < json.size() && json[p] == ',') {
            ++p;
        } else if (p < json.size() && json[p] == '}') {
            break;
        } else {
            return false;
        }
    }
    return false;
}

bool write_file_user_only(const std::wstring& path, const std::string& content, std::string& err) {
    std::wstring tmp_path = path + L".tmp";
    UserOnlySecurityAttributes sa(false);
    HANDLE h = CreateFileW(
        tmp_path.c_str(),
        GENERIC_WRITE,
        0,
        sa.get(),
        CREATE_ALWAYS,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        err = "failed to create config file (winerr " + std::to_string(GetLastError()) + ")";
        return false;
    }
    DWORD written = 0;
    BOOL ok = WriteFile(h, content.data(), static_cast<DWORD>(content.size()), &written, nullptr);
    FlushFileBuffers(h);
    CloseHandle(h);
    if (!ok || written != content.size()) {
        DeleteFileW(tmp_path.c_str());
        err = "failed to write config file";
        return false;
    }
    if (!MoveFileExW(tmp_path.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        DeleteFileW(tmp_path.c_str());
        err = "failed to replace config file (winerr " + std::to_string(GetLastError()) + ")";
        return false;
    }
    if (sa.sd()) {
        SetFileSecurityW(path.c_str(), DACL_SECURITY_INFORMATION, sa.sd());
    }
    return true;
}

} // namespace

std::string base64_encode(const uint8_t* data, size_t len) {
    static constexpr char kStdAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    return encode_b64_impl(data, len, kStdAlphabet, true);
}

std::string base64_encode(const std::string& data) {
    return base64_encode(reinterpret_cast<const uint8_t*>(data.data()), data.size());
}

std::string base64url_encode(const uint8_t* data, size_t len) {
    static constexpr char kUrlAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    return encode_b64_impl(data, len, kUrlAlphabet, false);
}

std::string json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned int>(c));
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
                break;
        }
    }
    return out;
}

bool constant_time_equals(const std::string& a, const std::string& b) {
    volatile size_t diff = a.size() ^ b.size();
    const size_t n = b.size();
    for (size_t i = 0; i < n; ++i) {
        uint8_t ca = (i < a.size()) ? static_cast<uint8_t>(a[i]) : 0;
        uint8_t cb = static_cast<uint8_t>(b[i]);
        diff |= static_cast<size_t>(ca ^ cb);
    }
    return diff == 0;
}

std::string generate_token() {
    std::array<uint8_t, 32> bytes{};
    if (!csprng_bytes(bytes.data(), bytes.size())) {
        return "";
    }
    return base64url_encode(bytes.data(), bytes.size());
}

std::wstring get_config_path() {
    DWORD env_len = GetEnvironmentVariableW(L"ACLOUDDROID_ANISETTE_HOME", nullptr, 0);
    if (env_len > 1) {
        std::wstring override_dir(env_len, L'\0');
        DWORD got = GetEnvironmentVariableW(L"ACLOUDDROID_ANISETTE_HOME", override_dir.data(), env_len);
        if (got > 0 && got < env_len) {
            override_dir.resize(got);
            while (!override_dir.empty() && (override_dir.back() == L'\\' || override_dir.back() == L'/')) {
                override_dir.pop_back();
            }
            if (!override_dir.empty()) {
                return override_dir + L"\\config.json";
            }
        }
    }

    std::wstring base;
    PWSTR appdata = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &appdata)) && appdata) {
        base = appdata;
        CoTaskMemFree(appdata);
    } else {
        wchar_t envBuf[MAX_PATH];
        DWORD len = GetEnvironmentVariableW(L"APPDATA", envBuf, MAX_PATH);
        if (len > 0 && len < MAX_PATH) {
            base.assign(envBuf, len);
        }
    }
    if (base.empty()) {
        base = L".";
    }
    return base + L"\\AClouDroidAnisette\\config.json";
}

bool load_or_create_config(Config& out, bool regenerate_token, std::string& err) {
    std::wstring path = get_config_path();
    size_t slash = path.find_last_of(L"\\/");
    std::wstring dir = (slash != std::wstring::npos) ? path.substr(0, slash) : L".";

    // Create any intermediate parent directories first (e.g. when ACLOUDDROID_ANISETTE_HOME is nested).
    {
        size_t pos = 0;
        while ((pos = dir.find_first_of(L"\\/", pos + 1)) != std::wstring::npos) {
            std::wstring sub = dir.substr(0, pos);
            if (sub.size() >= 2 && sub.back() != L':') {
                CreateDirectoryW(sub.c_str(), nullptr);
            }
        }
    }

    UserOnlySecurityAttributes dir_sa(true);
    if (!CreateDirectoryW(dir.c_str(), dir_sa.get())) {
        DWORD e = GetLastError();
        if (e != ERROR_ALREADY_EXISTS) {
            err = "failed to create config directory (winerr " + std::to_string(e) + ")";
            return false;
        }
    } else if (dir_sa.sd()) {
        SetFileSecurityW(dir.c_str(), DACL_SECURITY_INFORMATION, dir_sa.sd());
    }

    bool dirty = false;
    {
        HANDLE h = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                               OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h != INVALID_HANDLE_VALUE) {
            LARGE_INTEGER sz{};
            if (GetFileSizeEx(h, &sz) && sz.QuadPart > 0 && sz.QuadPart < 65536) {
                std::string content(static_cast<size_t>(sz.QuadPart), '\0');
                DWORD readBytes = 0;
                if (ReadFile(h, content.data(), static_cast<DWORD>(content.size()), &readBytes, nullptr)) {
                    content.resize(readBytes);
                    // Legacy "deviceId" fields in older config.json files are ignored.
                    parse_flat_json_string(content, "token", out.token);
                }
            }
            CloseHandle(h);
        }
    }

    if (regenerate_token || out.token.empty()) {
        out.token = generate_token();
        if (out.token.empty()) {
            err = "OS CSPRNG failed while generating token";
            return false;
        }
        dirty = true;
    }

    if (dirty) {
        std::string json = "{\n"
                           "  \"token\": \"" + json_escape(out.token) + "\"\n"
                           "}\n";
        if (!write_file_user_only(path, json, err)) {
            return false;
        }
    }
    return true;
}

} // namespace anisette
