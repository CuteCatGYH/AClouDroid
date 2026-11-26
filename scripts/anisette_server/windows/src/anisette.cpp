#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <shlobj.h>
#include <winver.h>

#include "anisette.hpp"
#include "config.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <vector>

namespace anisette {

namespace {

constexpr const char* kRoutingInfo = "17106176";

using ObjcGetClassFn = void* (__cdecl *)(const char*);
using SelRegisterNameFn = void* (__cdecl *)(const char*);
using MsgSend0Fn = void* (__cdecl *)(void*, void*);
using MsgSend1Fn = void* (__cdecl *)(void*, void*, void*);
using MsgSendCStrFn = void* (__cdecl *)(void*, void*, const char*);
using MsgSendRetCStrFn = const char* (__cdecl *)(void*, void*);
using MsgSendVoid0Fn = void (__cdecl *)(void*, void*);

using CopyOTPHeadersForDSIDFn = void* (__cdecl *)(void* cf_dsid);
using FnRetCF0 = void* (__cdecl *)();
using FnRetCStr0 = const char* (__cdecl *)();

using CFStringCreateWithCStringFn = void* (__cdecl *)(void* alloc, const char* cstr, uint32_t encoding);
using CFDictionaryGetValueFn = void* (__cdecl *)(void* dict, void* key);
using CFStringGetCStringFn = unsigned char (__cdecl *)(void* str, char* buf, int64_t bufSize, uint32_t encoding);
using CFStringGetLengthFn = int64_t (__cdecl *)(void* str);
using CFGetTypeIDFn = uint64_t (__cdecl *)(void* cf);
using CFStringGetTypeIDFn = uint64_t (__cdecl *)();
using CFCopyDescriptionFn = void* (__cdecl *)(void* cf);
using CFReleaseFn = void (__cdecl *)(void* cf);

constexpr uint32_t kCFStringEncodingUTF8 = 0x08000100;

// Plain-C SEH wrappers (no C++ objects with destructors inside __try under /EHsc).
static void safe_copy_cstr(char* dst, size_t dst_size, const char* src) {
    if (!dst || dst_size == 0) return;
    if (!src) {
        dst[0] = '\0';
        return;
    }
    size_t i = 0;
    for (; i + 1 < dst_size && src[i] != '\0'; ++i) {
        dst[i] = src[i];
    }
    dst[i] = '\0';
}

static bool seh_call_ret_cf0(FnRetCF0 fn, void** out_cf, DWORD* out_exc) {
    *out_cf = nullptr;
    *out_exc = 0;
    __try {
        *out_cf = fn();
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        *out_exc = GetExceptionCode();
        return false;
    }
}

static bool seh_call_ret_cstr0(FnRetCStr0 fn, char* buf, size_t buf_size, DWORD* out_exc) {
    *out_exc = 0;
    if (buf && buf_size > 0) buf[0] = '\0';
    __try {
        const char* s = fn();
        safe_copy_cstr(buf, buf_size, s);
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        *out_exc = GetExceptionCode();
        return false;
    }
}

std::string format_exc_hex(DWORD exc) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "0x%08lX", static_cast<unsigned long>(exc));
    return std::string(buf);
}

struct PeSections {
    const uint8_t* base = nullptr;
    const IMAGE_SECTION_HEADER* text = nullptr;
    const IMAGE_SECTION_HEADER* rdata = nullptr;
};

bool get_pe_sections(HMODULE hMod, PeSections& out) {
    if (!hMod) return false;
    auto base = reinterpret_cast<const uint8_t*>(hMod);
    auto dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
    auto nt = reinterpret_cast<const IMAGE_NT_HEADERS64*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;

    auto sec = IMAGE_FIRST_SECTION(nt);
    const IMAGE_SECTION_HEADER* textSec = nullptr;
    const IMAGE_SECTION_HEADER* rdataSec = nullptr;
    for (WORD i = 0; i < nt->FileHeader.NumberOfSections; ++i) {
        if (std::strncmp(reinterpret_cast<const char*>(sec[i].Name), ".text", 5) == 0) {
            textSec = &sec[i];
        } else if (std::strncmp(reinterpret_cast<const char*>(sec[i].Name), ".rdata", 6) == 0) {
            rdataSec = &sec[i];
        }
    }
    if (!textSec || !rdataSec) return false;
    out.base = base;
    out.text = textSec;
    out.rdata = rdataSec;
    return true;
}

// Resolve enclosing function entry via PE x64 .pdata, following MSVC shrink-wrap chains.
uintptr_t resolve_pdata_fn_entry(uintptr_t addr, DWORD64* out_image_base, PRUNTIME_FUNCTION* out_rf) {
    DWORD64 imageBase = 0;
    PRUNTIME_FUNCTION rf = RtlLookupFunctionEntry(static_cast<DWORD64>(addr), &imageBase, nullptr);
    for (int hops = 0; rf && imageBase != 0 && hops < 8; ++hops) {
        const uint8_t* ui = reinterpret_cast<const uint8_t*>(imageBase + rf->UnwindData);
        uint8_t flags = static_cast<uint8_t>(ui[0] >> 3);
        if ((flags & UNW_FLAG_CHAININFO) == 0) {
            break;
        }
        uint8_t countOfCodes = ui[2];
        size_t codesAligned = (static_cast<size_t>(countOfCodes) + 1u) & ~static_cast<size_t>(1u);
        rf = reinterpret_cast<PRUNTIME_FUNCTION>(const_cast<uint8_t*>(ui + 4 + codesAligned * 2));
    }
    if (out_image_base) *out_image_base = imageBase;
    if (out_rf) *out_rf = rf;
    if (!rf || imageBase == 0) return 0;
    return static_cast<uintptr_t>(imageBase + rf->BeginAddress);
}

// Return addresses of exact NUL-terminated strings matching `needle` in .rdata.
std::vector<uintptr_t> find_rdata_string_addrs(const PeSections& pe, const char* needle) {
    std::vector<uintptr_t> exact_start;
    std::vector<uintptr_t> any_match;
    if (!pe.base || !pe.rdata || !needle) return exact_start;

    size_t needleLen = std::strlen(needle) + 1; // include NUL terminator
    const uint8_t* rdataStart = pe.base + pe.rdata->VirtualAddress;
    size_t rdataSize = pe.rdata->Misc.VirtualSize;
    if (rdataSize < needleLen) return exact_start;

    for (size_t i = 0; i + needleLen <= rdataSize; ++i) {
        if (std::memcmp(rdataStart + i, needle, needleLen) == 0) {
            uintptr_t addr = reinterpret_cast<uintptr_t>(rdataStart + i);
            if (i == 0 || rdataStart[i - 1] == 0) {
                exact_start.push_back(addr);
            } else {
                any_match.push_back(addr);
            }
        }
    }
    return !exact_start.empty() ? exact_start : any_match;
}

// Find all enclosing functions in .text referencing a NUL-terminated .rdata string via RIP-relative LEA.
std::vector<void*> find_all_fns_referencing_string(HMODULE hMod, const char* needle) {
    std::vector<void*> results;
    PeSections pe;
    if (!get_pe_sections(hMod, pe)) return results;

    auto strAddrs = find_rdata_string_addrs(pe, needle);
    if (strAddrs.empty()) return results;

    const uint8_t* textStart = pe.base + pe.text->VirtualAddress;
    size_t textSize = pe.text->Misc.VirtualSize;
    for (size_t i = 0; i + 7 <= textSize; ++i) {
        if ((textStart[i] == 0x48 || textStart[i] == 0x4C) &&
            textStart[i + 1] == 0x8D &&
            (textStart[i + 2] & 0xC7) == 0x05) {
            int32_t disp = 0;
            std::memcpy(&disp, textStart + i + 3, sizeof(disp));
            uintptr_t instrAddr = reinterpret_cast<uintptr_t>(textStart + i);
            uintptr_t target = static_cast<uintptr_t>(static_cast<intptr_t>(instrAddr + 7) + disp);
            if (std::find(strAddrs.begin(), strAddrs.end(), target) != strAddrs.end()) {
                uintptr_t entry = resolve_pdata_fn_entry(instrAddr, nullptr, nullptr);
                if (entry != 0) {
                    void* fn = reinterpret_cast<void*>(entry);
                    if (std::find(results.begin(), results.end(), fn) == results.end()) {
                        results.push_back(fn);
                    }
                }
            }
        }
    }
    return results;
}

// Find the first RIP-relative LEA in [rangeStart, rangeEnd) targeting `needle` in .rdata.
uintptr_t find_lea_to_string_in_range(const PeSections& pe, const char* needle, uintptr_t rangeStart, uintptr_t rangeEnd) {
    if (rangeStart >= rangeEnd) return 0;
    auto strAddrs = find_rdata_string_addrs(pe, needle);
    if (strAddrs.empty()) return 0;

    const uint8_t* p = reinterpret_cast<const uint8_t*>(rangeStart);
    size_t sz = rangeEnd - rangeStart;
    for (size_t i = 0; i + 7 <= sz; ++i) {
        if ((p[i] == 0x48 || p[i] == 0x4C) && p[i + 1] == 0x8D && (p[i + 2] & 0xC7) == 0x05) {
            int32_t disp = 0;
            std::memcpy(&disp, p + i + 3, sizeof(disp));
            uintptr_t instrAddr = rangeStart + i;
            uintptr_t target = static_cast<uintptr_t>(static_cast<intptr_t>(instrAddr + 7) + disp);
            if (std::find(strAddrs.begin(), strAddrs.end(), target) != strAddrs.end()) {
                return instrAddr;
            }
        }
    }
    return 0;
}

// Collect direct E8 rel32 call targets in [start, end) that point to a valid .pdata function start in .text.
std::vector<void*> collect_rel32_calls_in_text(const PeSections& pe, uintptr_t start, uintptr_t end) {
    std::vector<void*> targets;
    if (!pe.base || !pe.text || start >= end) return targets;

    uintptr_t textLo = reinterpret_cast<uintptr_t>(pe.base + pe.text->VirtualAddress);
    uintptr_t textHi = textLo + pe.text->Misc.VirtualSize;

    const uint8_t* p = reinterpret_cast<const uint8_t*>(start);
    size_t sz = end - start;
    for (size_t i = 0; i + 5 <= sz; ++i) {
        if (p[i] == 0xE8) {
            int32_t disp = 0;
            std::memcpy(&disp, p + i + 1, sizeof(disp));
            uintptr_t tgt = static_cast<uintptr_t>(static_cast<intptr_t>(start + i + 5) + disp);
            if (tgt >= textLo && tgt < textHi) {
                uintptr_t entry = resolve_pdata_fn_entry(tgt, nullptr, nullptr);
                if (entry == tgt) {
                    targets.push_back(reinterpret_cast<void*>(tgt));
                }
            }
        }
    }
    return targets;
}

// Locate AOSUtilities::copyOTPHeadersForDSID(CFStringRef) in pure-C/C++ AOSKit.dll (iCloud 15+ Store).
void* find_copy_otp_headers_fn(HMODULE hAOSKit) {
    auto fns = find_all_fns_referencing_string(hAOSKit, "copyOTPHeadersForDSID");
    return fns.empty() ? nullptr : fns.front();
}

struct AoskitIdentityFns {
    void* get_device_id = nullptr;
    void* get_local_user_uuid = nullptr;
    void* client_info_os = nullptr;
    void* client_info_app = nullptr;
};

// Locate AOSKit internal device-identity functions via .rdata string anchors + .pdata (no hard-coded RVAs).
bool locate_aoskit_identity_fns(HMODULE hAOSKit, AoskitIdentityFns& out, std::string& err) {
    out = {};
    PeSections pe;
    if (!get_pe_sections(hAOSKit, pe)) {
        err = "failed to inspect AOSKit.dll PE sections";
        return false;
    }

    auto devIdFns = find_all_fns_referencing_string(hAOSKit, "_GetDeviceId");
    auto fmtFns = find_all_fns_referencing_string(hAOSKit, "<%s> <%s> <%s>");
    auto luFns = find_all_fns_referencing_string(hAOSKit, "Local UUID: <%@>");

    auto get_fn_bounds = [](void* fn, uintptr_t& fnStart, uintptr_t& fnEnd) -> DWORD {
        DWORD64 imgBase = 0;
        PRUNTIME_FUNCTION rf = nullptr;
        uintptr_t entry = resolve_pdata_fn_entry(reinterpret_cast<uintptr_t>(fn), &imgBase, &rf);
        if (!entry || !rf || !imgBase) {
            fnStart = 0;
            fnEnd = 0;
            return 0;
        }
        fnStart = static_cast<uintptr_t>(imgBase + rf->BeginAddress);
        fnEnd = static_cast<uintptr_t>(imgBase + rf->EndAddress);
        return (fnEnd > fnStart) ? static_cast<DWORD>(fnEnd - fnStart) : 0;
    };

    // 1) Standalone _GetDeviceId(): small function referencing "_GetDeviceId" (not the request builder).
    DWORD bestDevSize = 0xFFFFFFFFu;
    for (void* fn : devIdFns) {
        if (std::find(fmtFns.begin(), fmtFns.end(), fn) != fmtFns.end()) continue;
        uintptr_t fnStart = 0, fnEnd = 0;
        DWORD fnSize = get_fn_bounds(fn, fnStart, fnEnd);
        if (fnSize > 0 && fnSize <= 0x200 && fnSize < bestDevSize) {
            bestDevSize = fnSize;
            out.get_device_id = fn;
        }
    }

    // Helper to order candidate functions, putting functions that also reference "_GetDeviceId" first.
    auto prioritize_with_devid = [&](std::vector<void*> cands) {
        std::stable_partition(cands.begin(), cands.end(), [&](void* fn) {
            return std::find(devIdFns.begin(), devIdFns.end(), fn) != devIdFns.end();
        });
        return cands;
    };

    // 2) Client-Info builders: the two direct E8 calls immediately preceding LEA "<%s> <%s> <%s>".
    for (void* fn : prioritize_with_devid(fmtFns)) {
        uintptr_t fnStart = 0, fnEnd = 0;
        if (get_fn_bounds(fn, fnStart, fnEnd) == 0) continue;
        uintptr_t leaFmt = find_lea_to_string_in_range(pe, "<%s> <%s> <%s>", fnStart, fnEnd);
        if (leaFmt <= fnStart) continue;

        static constexpr uintptr_t kWindows[] = { 0x20, 0x40, 0x60 };
        for (uintptr_t w : kWindows) {
            uintptr_t scanStart = (leaFmt > fnStart + w) ? (leaFmt - w) : fnStart;
            auto calls = collect_rel32_calls_in_text(pe, scanStart, leaFmt);
            if (calls.size() >= 2) {
                out.client_info_app = calls[calls.size() - 2];
                out.client_info_os = calls[calls.size() - 1];
                break;
            }
        }
        if (out.client_info_os && out.client_info_app) break;
    }

    // 3) _GetLocalUserUUID(): direct E8 call preceding LEA "Local UUID: <%@>".
    for (void* fn : prioritize_with_devid(luFns)) {
        uintptr_t fnStart = 0, fnEnd = 0;
        if (get_fn_bounds(fn, fnStart, fnEnd) == 0) continue;
        uintptr_t leaLocal = find_lea_to_string_in_range(pe, "Local UUID: <%@>", fnStart, fnEnd);
        if (leaLocal <= fnStart) continue;

        // First try [leaLocal - 0x50, leaLocal - 0x20) (avoids any trailing log helper),
        // then broaden to [leaLocal - 0x70, leaLocal) if compiler layout shifted.
        if (leaLocal > fnStart + 0x20) {
            uintptr_t scanStart = (leaLocal > fnStart + 0x50) ? (leaLocal - 0x50) : fnStart;
            uintptr_t scanEnd = leaLocal - 0x20;
            auto calls = collect_rel32_calls_in_text(pe, scanStart, scanEnd);
            if (!calls.empty()) {
                out.get_local_user_uuid = calls.front();
                break;
            }
        }
        uintptr_t wideStart = (leaLocal > fnStart + 0x70) ? (leaLocal - 0x70) : fnStart;
        auto calls = collect_rel32_calls_in_text(pe, wideStart, leaLocal);
        if (!calls.empty()) {
            out.get_local_user_uuid = calls.front();
            break;
        }
    }

    std::vector<std::string> missing;
    if (!out.get_device_id) missing.push_back("_GetDeviceId");
    if (!out.get_local_user_uuid) missing.push_back("_GetLocalUserUUID");
    if (!out.client_info_os || !out.client_info_app) missing.push_back("Client-Info builders");

    if (!missing.empty()) {
        err = "failed to locate AOSKit internal function(s): ";
        for (size_t i = 0; i < missing.size(); ++i) {
            if (i > 0) err += ", ";
            err += missing[i];
        }
        return false;
    }
    return true;
}

bool file_exists(const std::wstring& path) {
    DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

bool dir_exists(const std::wstring& path) {
    DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY);
}

std::wstring join_path(const std::wstring& a, const std::wstring& b) {
    if (a.empty()) return b;
    if (a.back() == L'\\' || a.back() == L'/') return a + b;
    return a + L"\\" + b;
}

std::string wide_to_utf8(const std::wstring& w) {
    if (w.empty()) return "";
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    if (n <= 0) return "";
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

// Extract version token from Store package name or path, e.g. "AppleInc.iCloud_15.10.39.0_x64__...".
std::string extract_version_from_store_name(const std::wstring& s) {
    if (s.empty()) return "";
    std::string utf8 = wide_to_utf8(s);
    std::string lower = utf8;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });

    const std::string marker = "appleinc.icloud_";
    size_t pos = lower.find(marker);
    if (pos == std::string::npos) return "";

    size_t verStart = pos + marker.size();
    size_t verEnd = utf8.find_first_of("_\\/", verStart);
    std::string ver = (verEnd == std::string::npos)
        ? utf8.substr(verStart)
        : utf8.substr(verStart, verEnd - verStart);
    if (ver.empty()) return "";

    bool has_dot = false;
    bool has_nonzero = false;
    for (unsigned char c : ver) {
        if (c == '.') {
            has_dot = true;
        } else if (std::isdigit(c)) {
            if (c != '0') has_nonzero = true;
        } else {
            return "";
        }
    }
    return (has_dot && has_nonzero) ? ver : "";
}

// Read FileVersion (major.minor.build.rev) from a PE executable's VS_FIXEDFILEINFO.
std::string read_exe_file_version(const std::wstring& exe_path) {
    if (!file_exists(exe_path)) return "";
    DWORD dummy = 0;
    DWORD sz = GetFileVersionInfoSizeW(exe_path.c_str(), &dummy);
    if (sz == 0) return "";
    std::vector<uint8_t> buf(sz);
    if (!GetFileVersionInfoW(exe_path.c_str(), 0, sz, buf.data())) return "";
    VS_FIXEDFILEINFO* ffi = nullptr;
    UINT len = 0;
    if (!VerQueryValueW(buf.data(), L"\\", reinterpret_cast<LPVOID*>(&ffi), &len) ||
        !ffi || len < sizeof(VS_FIXEDFILEINFO)) {
        return "";
    }
    WORD v1 = HIWORD(ffi->dwFileVersionMS);
    WORD v2 = LOWORD(ffi->dwFileVersionMS);
    WORD v3 = HIWORD(ffi->dwFileVersionLS);
    WORD v4 = LOWORD(ffi->dwFileVersionLS);
    if (v1 == 0 && v2 == 0 && v3 == 0 && v4 == 0) return "";
    char out[64];
    std::snprintf(out, sizeof(out), "%u.%u.%u.%u", v1, v2, v3, v4);
    return std::string(out);
}

std::string detect_icloud_version(
    const std::wstring& store_pkg_full_name,
    const std::wstring& orig_dir,
    const std::wstring& classic_services_dir) {
    std::string ver = extract_version_from_store_name(store_pkg_full_name);
    if (!ver.empty()) return ver;

    ver = extract_version_from_store_name(orig_dir);
    if (!ver.empty()) return ver;

    static const wchar_t* kExeNames[] = {
        L"iCloud.exe",
        L"iCloudServices.exe",
        L"iCloudHome.exe",
        L"iCloudDrive.exe",
        L"iCloudPhotos.exe",
    };

    std::vector<std::wstring> dirs;
    if (!orig_dir.empty()) dirs.push_back(orig_dir);
    if (!classic_services_dir.empty() && classic_services_dir != orig_dir) {
        dirs.push_back(classic_services_dir);
    }

    for (const auto& d : dirs) {
        for (const wchar_t* exe : kExeNames) {
            ver = read_exe_file_version(join_path(d, exe));
            if (!ver.empty()) return ver;
        }
    }
    return "";
}

// Replace placeholder zero version in "(com.apple.iCloud/0.0)" with the installed iCloud version.
std::string fix_client_info_app_version(std::string app_part, const std::string& icloud_ver) {
    if (icloud_ver.empty()) return app_part;
    const std::string prefix = "com.apple.iCloud/";
    size_t pos = app_part.find(prefix);
    if (pos == std::string::npos) return app_part;

    size_t verStart = pos + prefix.size();
    size_t verEnd = verStart;
    while (verEnd < app_part.size() && app_part[verEnd] != ')' && app_part[verEnd] != ' ') {
        ++verEnd;
    }
    if (verEnd <= verStart) return app_part;

    bool all_zero_or_dot = true;
    for (size_t i = verStart; i < verEnd; ++i) {
        if (app_part[i] != '0' && app_part[i] != '.') {
            all_zero_or_dot = false;
            break;
        }
    }
    if (all_zero_or_dot) {
        app_part.replace(verStart, verEnd - verStart, icloud_ver);
    }
    return app_part;
}

// Discover Microsoft Store iCloud package via Win32 AppModel runtime APIs.
std::wstring find_store_icloud_via_appmodel(std::wstring* out_pkg_full_name) {
    if (out_pkg_full_name) out_pkg_full_name->clear();
    HMODULE hKernel = GetModuleHandleW(L"kernel32.dll");
    if (!hKernel) return L"";

    using FindPackagesByPackageFamilyFn = LONG (WINAPI *)(
        PCWSTR, UINT32, UINT32*, PWSTR*, UINT32*, WCHAR*, UINT32*);
    using GetPackagePathByFullNameFn = LONG (WINAPI *)(PCWSTR, UINT32*, PWSTR);

    auto pFind = reinterpret_cast<FindPackagesByPackageFamilyFn>(
        GetProcAddress(hKernel, "FindPackagesByPackageFamily"));
    auto pGetPath = reinterpret_cast<GetPackagePathByFullNameFn>(
        GetProcAddress(hKernel, "GetPackagePathByFullName"));
    if (!pFind || !pGetPath) return L"";

    constexpr UINT32 kPackageFilterHead = 0x00000010;
    const wchar_t* kFamily = L"AppleInc.iCloud_nzyj5cx40ttqa";

    UINT32 count = 0;
    UINT32 bufLen = 0;
    LONG rc = pFind(kFamily, kPackageFilterHead, &count, nullptr, &bufLen, nullptr, nullptr);
    if (rc != ERROR_INSUFFICIENT_BUFFER || count == 0 || bufLen == 0) {
        return L"";
    }

    std::vector<PWSTR> names(count);
    std::vector<WCHAR> buf(bufLen);
    rc = pFind(kFamily, kPackageFilterHead, &count, names.data(), &bufLen, buf.data(), nullptr);
    if (rc != ERROR_SUCCESS || count == 0) {
        return L"";
    }

    for (UINT32 i = 0; i < count; ++i) {
        UINT32 pathLen = 0;
        if (pGetPath(names[i], &pathLen, nullptr) == ERROR_INSUFFICIENT_BUFFER && pathLen > 0) {
            std::wstring pkgPath(pathLen, L'\0');
            if (pGetPath(names[i], &pathLen, pkgPath.data()) == ERROR_SUCCESS) {
                while (!pkgPath.empty() && pkgPath.back() == L'\0') pkgPath.pop_back();
                std::wstring candidate = join_path(pkgPath, L"iCloud");
                if (file_exists(join_path(candidate, L"AOSKit.dll")) &&
                    file_exists(join_path(candidate, L"objc.dll"))) {
                    if (out_pkg_full_name && names[i]) {
                        *out_pkg_full_name = names[i];
                    }
                    return candidate;
                }
            }
        }
    }
    return L"";
}

// Fallback scan of C:\Program Files\WindowsApps\AppleInc.iCloud_*_x64__*\iCloud
std::wstring find_store_icloud_via_scan() {
    wchar_t pfBuf[MAX_PATH];
    std::wstring pf = L"C:\\Program Files";
    DWORD len = GetEnvironmentVariableW(L"ProgramFiles", pfBuf, MAX_PATH);
    if (len > 0 && len < MAX_PATH) {
        pf.assign(pfBuf, len);
    }

    std::wstring pattern = join_path(join_path(pf, L"WindowsApps"), L"AppleInc.iCloud_*_x64__*");
    WIN32_FIND_DATAW ffd{};
    HANDLE hFind = FindFirstFileW(pattern.c_str(), &ffd);
    if (hFind == INVALID_HANDLE_VALUE) {
        return L"";
    }

    std::vector<std::wstring> matches;
    do {
        if (ffd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            std::wstring candidate = join_path(
                join_path(join_path(pf, L"WindowsApps"), ffd.cFileName),
                L"iCloud");
            if (file_exists(join_path(candidate, L"AOSKit.dll")) &&
                file_exists(join_path(candidate, L"objc.dll"))) {
                matches.push_back(candidate);
            }
        }
    } while (FindNextFileW(hFind, &ffd));
    FindClose(hFind);

    if (matches.empty()) return L"";
    std::sort(matches.begin(), matches.end());
    return matches.back();
}

struct ClassicDirs {
    std::wstring aas_dir;      // Apple Application Support
    std::wstring services_dir; // Internet Services
};

bool find_classic_icloud(ClassicDirs& out) {
    wchar_t cpfBuf[MAX_PATH];
    DWORD len = GetEnvironmentVariableW(L"CommonProgramFiles", cpfBuf, MAX_PATH);
    std::wstring cpf = (len > 0 && len < MAX_PATH)
        ? std::wstring(cpfBuf, len)
        : L"C:\\Program Files\\Common Files";

    std::wstring aas = join_path(cpf, L"Apple\\Apple Application Support");
    std::wstring svc = join_path(cpf, L"Apple\\Internet Services");
    if (file_exists(join_path(aas, L"objc.dll")) &&
        file_exists(join_path(aas, L"Foundation.dll")) &&
        file_exists(join_path(svc, L"AOSKit.dll"))) {
        out.aas_dir = aas;
        out.services_dir = svc;
        return true;
    }
    return false;
}

void prepend_to_path(const std::wstring& dir) {
    DWORD len = GetEnvironmentVariableW(L"PATH", nullptr, 0);
    std::wstring cur;
    if (len > 0) {
        cur.resize(len);
        DWORD got = GetEnvironmentVariableW(L"PATH", cur.data(), len);
        cur.resize(got);
    }
    std::wstring updated = dir;
    if (!cur.empty()) {
        updated += L";";
        updated += cur;
    }
    SetEnvironmentVariableW(L"PATH", updated.c_str());
}

void ensure_dir_recursive(const std::wstring& path) {
    size_t pos = 0;
    while ((pos = path.find_first_of(L"\\/", pos + 1)) != std::wstring::npos) {
        std::wstring sub = path.substr(0, pos);
        if (sub.size() >= 2 && sub.back() != L':') {
            CreateDirectoryW(sub.c_str(), nullptr);
        }
    }
    CreateDirectoryW(path.c_str(), nullptr);
}

// Copy a file by reading its data stream (avoids carrying over Store AppX EAs/ACLs).
bool copy_file_clean(const std::wstring& src, const std::wstring& dst) {
    WIN32_FILE_ATTRIBUTE_DATA src_info{};
    if (!GetFileAttributesExW(src.c_str(), GetFileExInfoStandard, &src_info)) {
        return false;
    }
    WIN32_FILE_ATTRIBUTE_DATA dst_info{};
    if (GetFileAttributesExW(dst.c_str(), GetFileExInfoStandard, &dst_info)) {
        if (src_info.nFileSizeHigh == dst_info.nFileSizeHigh &&
            src_info.nFileSizeLow == dst_info.nFileSizeLow &&
            CompareFileTime(&src_info.ftLastWriteTime, &dst_info.ftLastWriteTime) == 0) {
            return true;
        }
    }

    HANDLE hIn = CreateFileW(src.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE,
                             nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hIn == INVALID_HANDLE_VALUE) {
        return false;
    }

    std::wstring tmp = dst + L".tmp";
    HANDLE hOut = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr,
                              CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hOut == INVALID_HANDLE_VALUE) {
        CloseHandle(hIn);
        return false;
    }

    std::vector<uint8_t> buf(262144);
    bool ok = true;
    for (;;) {
        DWORD nr = 0;
        if (!ReadFile(hIn, buf.data(), static_cast<DWORD>(buf.size()), &nr, nullptr)) {
            ok = false;
            break;
        }
        if (nr == 0) break;
        DWORD nw = 0;
        if (!WriteFile(hOut, buf.data(), nr, &nw, nullptr) || nw != nr) {
            ok = false;
            break;
        }
    }
    if (ok) {
        SetFileTime(hOut, nullptr, nullptr, &src_info.ftLastWriteTime);
    }
    CloseHandle(hIn);
    CloseHandle(hOut);

    if (!ok) {
        DeleteFileW(tmp.c_str());
        return false;
    }
    if (!MoveFileExW(tmp.c_str(), dst.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        DeleteFileW(tmp.c_str());
        return false;
    }
    return true;
}

void copy_dir_recursive(const std::wstring& src_dir, const std::wstring& dst_dir) {
    if (!dir_exists(src_dir)) return;
    ensure_dir_recursive(dst_dir);
    WIN32_FIND_DATAW fd{};
    HANDLE h = FindFirstFileW(join_path(src_dir, L"*").c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        if (wcscmp(fd.cFileName, L".") == 0 || wcscmp(fd.cFileName, L"..") == 0) continue;
        std::wstring s = join_path(src_dir, fd.cFileName);
        std::wstring d = join_path(dst_dir, fd.cFileName);
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            copy_dir_recursive(s, d);
        } else {
            copy_file_clean(s, d);
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
}

// Microsoft Store (WindowsApps) files grant BUILTIN\Users FILE_READ but restrict in-place
// FILE_EXECUTE via a conditional WIN://SYSAPPID ACE, causing LoadLibraryW to fail with
// ERROR_ACCESS_DENIED (5). Stage readable runtime DLLs & resources into %LOCALAPPDATA%.
bool stage_store_icloud_dir(const std::wstring& src_dir, std::wstring& out_cache_dir, std::string& err) {
    std::wstring base;
    PWSTR localAppData = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &localAppData)) && localAppData) {
        base = localAppData;
        CoTaskMemFree(localAppData);
    } else {
        wchar_t envBuf[MAX_PATH]{};
        DWORD len = GetEnvironmentVariableW(L"LOCALAPPDATA", envBuf, MAX_PATH);
        if (len > 0 && len < MAX_PATH) base.assign(envBuf, len);
    }
    if (base.empty()) {
        err = "cannot resolve LOCALAPPDATA to cache Microsoft Store iCloud DLLs";
        return false;
    }

    std::wstring trimmed = src_dir;
    while (!trimmed.empty() && (trimmed.back() == L'\\' || trimmed.back() == L'/')) {
        trimmed.pop_back();
    }
    std::wstring key = L"default";
    size_t lastSep = trimmed.find_last_of(L"\\/");
    if (lastSep != std::wstring::npos) {
        std::wstring tail = trimmed.substr(lastSep + 1);
        if (_wcsicmp(tail.c_str(), L"iCloud") == 0) {
            std::wstring parent = trimmed.substr(0, lastSep);
            size_t parentSep = parent.find_last_of(L"\\/");
            key = (parentSep == std::wstring::npos) ? parent : parent.substr(parentSep + 1);
        } else {
            key = tail;
        }
    }

    std::wstring cache_dir = join_path(
        join_path(join_path(base, L"AClouDroidAnisette"), L"icloud-cache"),
        key);
    ensure_dir_recursive(cache_dir);

    static const wchar_t* kRuntimeFiles[] = {
        L"objc.dll",
        L"CoreFoundation.dll",
        L"Foundation.dll",
        L"AOSKit.dll",
        L"CFNetwork.dll",
        L"libdispatch.dll",
        L"CoreADI64.dll",
        L"ASL.dll",
        L"libicuin.dll",
        L"libicuuc.dll",
        L"libxml2.dll",
        L"SQLite3.dll",
        L"zlib1.dll",
        L"pthreadVC2.dll",
        L"AppleVersions.dll",
        L"lskd.rl",
    };

    for (const wchar_t* name : kRuntimeFiles) {
        std::wstring s = join_path(src_dir, name);
        if (file_exists(s)) {
            if (!copy_file_clean(s, join_path(cache_dir, name))) {
                err = "failed to stage " + wide_to_utf8(name);
                return false;
            }
        }
    }

    WIN32_FIND_DATAW ffd{};
    HANDLE hFind = FindFirstFileW(join_path(src_dir, L"icudt*.dll").c_str(), &ffd);
    if (hFind != INVALID_HANDLE_VALUE) {
        do {
            if (!(ffd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
                copy_file_clean(join_path(src_dir, ffd.cFileName), join_path(cache_dir, ffd.cFileName));
            }
        } while (FindNextFileW(hFind, &ffd));
        FindClose(hFind);
    }

    if (!file_exists(join_path(cache_dir, L"objc.dll")) ||
        !file_exists(join_path(cache_dir, L"AOSKit.dll"))) {
        err = "failed to stage iCloud DLLs into local cache";
        return false;
    }

    static const wchar_t* kResDirs[] = {
        L"CoreFoundation.resources",
        L"Foundation.resources",
        L"CFNetwork.resources",
    };
    for (const wchar_t* r : kResDirs) {
        copy_dir_recursive(join_path(src_dir, r), join_path(cache_dir, r));
    }

    out_cache_dir = cache_dir;
    return true;
}

std::string format_utc_now() {
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

std::string get_local_timezone_abbr() {
    DYNAMIC_TIME_ZONE_INFORMATION dtzi{};
    DWORD mode = GetDynamicTimeZoneInformation(&dtzi);
    if (mode == TIME_ZONE_ID_INVALID) {
        return "UTC";
    }
    bool dst = (mode == TIME_ZONE_ID_DAYLIGHT);
    std::string key = wide_to_utf8(dtzi.TimeZoneKeyName);

    struct TzMap {
        const char* key;
        const char* std_abbr;
        const char* dst_abbr;
    };
    static constexpr TzMap kTable[] = {
        {"UTC", "UTC", "UTC"},
        {"GMT Standard Time", "GMT", "BST"},
        {"Greenwich Standard Time", "GMT", "GMT"},
        {"China Standard Time", "CST", "CST"},
        {"Taipei Standard Time", "CST", "CST"},
        {"Singapore Standard Time", "SGT", "SGT"},
        {"Tokyo Standard Time", "JST", "JST"},
        {"Korea Standard Time", "KST", "KST"},
        {"India Standard Time", "IST", "IST"},
        {"Eastern Standard Time", "EST", "EDT"},
        {"Central Standard Time", "CST", "CDT"},
        {"Mountain Standard Time", "MST", "MDT"},
        {"Pacific Standard Time", "PST", "PDT"},
        {"Alaskan Standard Time", "AKST", "AKDT"},
        {"Hawaiian Standard Time", "HST", "HST"},
        {"Atlantic Standard Time", "AST", "ADT"},
        {"W. Europe Standard Time", "CET", "CEST"},
        {"Central Europe Standard Time", "CET", "CEST"},
        {"Central European Standard Time", "CET", "CEST"},
        {"Romance Standard Time", "CET", "CEST"},
        {"E. Europe Standard Time", "EET", "EEST"},
        {"FLE Standard Time", "EET", "EEST"},
        {"GTB Standard Time", "EET", "EEST"},
        {"Russian Standard Time", "MSK", "MSK"},
        {"AUS Eastern Standard Time", "AEST", "AEDT"},
        {"E. Australia Standard Time", "AEST", "AEST"},
        {"W. Australia Standard Time", "AWST", "AWST"},
        {"New Zealand Standard Time", "NZST", "NZDT"},
        {"SE Asia Standard Time", "ICT", "ICT"},
    };
    for (const auto& entry : kTable) {
        if (key == entry.key) {
            return dst ? entry.dst_abbr : entry.std_abbr;
        }
    }

    // Fallback: initials of English TimeZoneKeyName if ASCII (e.g. "Mountain Standard Time" -> "MST")
    if (!key.empty()) {
        std::string abbr;
        bool in_word = false;
        bool all_ascii = true;
        for (unsigned char c : key) {
            if (c >= 0x80) {
                all_ascii = false;
                break;
            }
            if (std::isalpha(c)) {
                if (!in_word) {
                    abbr.push_back(static_cast<char>(std::toupper(c)));
                    in_word = true;
                }
            } else {
                in_word = false;
            }
        }
        if (all_ascii && abbr.size() >= 2 && abbr.size() <= 5) {
            return abbr;
        }
    }
    return "UTC";
}

std::string get_apple_locale() {
    wchar_t buf[LOCALE_NAME_MAX_LENGTH]{};
    int len = GetUserDefaultLocaleName(buf, LOCALE_NAME_MAX_LENGTH);
    if (len <= 1) {
        return "en_US";
    }
    std::string raw = wide_to_utf8(std::wstring(buf, static_cast<size_t>(len - 1)));
    // Split on '-' or '_'
    std::vector<std::string> parts;
    size_t start = 0;
    while (start <= raw.size()) {
        size_t pos = raw.find_first_of("-_", start);
        if (pos == std::string::npos) {
            parts.push_back(raw.substr(start));
            break;
        }
        parts.push_back(raw.substr(start, pos - start));
        start = pos + 1;
    }
    if (parts.empty() || parts[0].empty()) {
        return "en_US";
    }
    if (parts.size() == 1) {
        return parts[0];
    }
    // e.g. zh-Hans-CN -> zh_CN; en-US -> en_US
    const std::string& lang = parts.front();
    const std::string& region = parts.back();
    return lang + "_" + region;
}

} // namespace

AnisetteProvider::AnisetteProvider(std::wstring icloud_dir_override)
    : icloud_dir_override_(std::move(icloud_dir_override)) {}

AnisetteProvider::~AnisetteProvider() {
    {
        std::lock_guard<std::mutex> lk(queue_mu_);
        stop_ = true;
    }
    cv_req_.notify_all();
    if (worker_.joinable()) {
        worker_.join();
    }
}

bool AnisetteProvider::init() {
    worker_ = std::thread(&AnisetteProvider::worker_loop, this);
    std::unique_lock<std::mutex> lk(queue_mu_);
    cv_done_.wait(lk, [this] { return init_done_; });
    return is_ready();
}

bool AnisetteProvider::is_ready() const {
    std::lock_guard<std::mutex> lk(state_mu_);
    return ready_;
}

std::string AnisetteProvider::init_error() const {
    std::lock_guard<std::mutex> lk(state_mu_);
    return init_err_;
}

std::wstring AnisetteProvider::icloud_dir_used() const {
    std::lock_guard<std::mutex> lk(state_mu_);
    return icloud_dir_used_;
}

bool AnisetteProvider::fetch(AnisetteData& out, std::string& err) {
    std::lock_guard<std::mutex> call_lk(call_mu_);
    if (!is_ready()) {
        err = init_error();
        if (err.empty()) err = "iCloud DLLs not loaded";
        return false;
    }

    Request req;
    req.out = &out;
    req.err = &err;

    {
        std::lock_guard<std::mutex> lk(queue_mu_);
        current_req_ = &req;
    }
    cv_req_.notify_one();

    {
        std::unique_lock<std::mutex> lk(queue_mu_);
        cv_done_.wait(lk, [&req] { return req.done; });
    }
    return req.ok;
}

void AnisetteProvider::worker_loop() {
    std::string err;
    bool ok = load_icloud_dlls_on_worker(err);
    {
        std::lock_guard<std::mutex> slk(state_mu_);
        ready_ = ok;
        init_err_ = err;
    }
    {
        std::lock_guard<std::mutex> qlk(queue_mu_);
        init_done_ = true;
    }
    cv_done_.notify_all();

    for (;;) {
        Request* req = nullptr;
        {
            std::unique_lock<std::mutex> lk(queue_mu_);
            cv_req_.wait(lk, [this] { return stop_ || current_req_ != nullptr; });
            if (stop_) break;
            req = current_req_;
            current_req_ = nullptr;
        }
        if (req) {
            req->ok = compute_on_worker(*req->out, *req->err);
            {
                std::lock_guard<std::mutex> lk(queue_mu_);
                req->done = true;
            }
            cv_done_.notify_all();
        }
    }
}

bool AnisetteProvider::load_icloud_dlls_on_worker(std::string& err) {
    std::wstring single_dir;
    std::wstring orig_dir;
    std::wstring store_pkg_full_name;
    ClassicDirs classic{};
    bool use_classic = false;

    if (!icloud_dir_override_.empty()) {
        if (!dir_exists(icloud_dir_override_)) {
            err = "specified --icloud-dir does not exist";
            return false;
        }
        single_dir = icloud_dir_override_;
        orig_dir = icloud_dir_override_;
    } else {
        single_dir = find_store_icloud_via_appmodel(&store_pkg_full_name);
        if (single_dir.empty()) {
            single_dir = find_store_icloud_via_scan();
        }
        if (single_dir.empty()) {
            if (find_classic_icloud(classic)) {
                use_classic = true;
                orig_dir = classic.services_dir;
            } else {
                err = "iCloud for Windows DLLs not found";
                return false;
            }
        } else {
            orig_dir = single_dir;
        }
    }

    icloud_version_ = detect_icloud_version(store_pkg_full_name, orig_dir, classic.services_dir);

    HMODULE hObjc = nullptr;
    HMODULE hCF = nullptr;
    HMODULE hFoundation = nullptr;
    HMODULE hAOSKit = nullptr;

    if (!use_classic) {
        SetDllDirectoryW(single_dir.c_str());
        SetCurrentDirectoryW(single_dir.c_str());

        hObjc = LoadLibraryW(L"objc.dll");
        if (!hObjc && GetLastError() == ERROR_ACCESS_DENIED) {
            std::wstring staged_dir;
            std::string stage_err;
            if (stage_store_icloud_dir(single_dir, staged_dir, stage_err)) {
                single_dir = staged_dir;
                prepend_to_path(single_dir);
                SetDllDirectoryW(single_dir.c_str());
                SetCurrentDirectoryW(single_dir.c_str());
                hObjc = LoadLibraryW(join_path(single_dir, L"objc.dll").c_str());
            } else {
                err = stage_err;
                return false;
            }
        } else if (hObjc) {
            prepend_to_path(single_dir);
        }
        {
            std::lock_guard<std::mutex> slk(state_mu_);
            icloud_dir_used_ = single_dir;
        }
        if (!hObjc) {
            err = "failed to load objc.dll (winerr " + std::to_string(GetLastError()) + ")";
            return false;
        }
        if (file_exists(join_path(single_dir, L"CoreFoundation.dll"))) {
            hCF = LoadLibraryW(join_path(single_dir, L"CoreFoundation.dll").c_str());
            (void)hCF;
        }
        hFoundation = LoadLibraryW(join_path(single_dir, L"Foundation.dll").c_str());
        if (!hFoundation) {
            err = "failed to load Foundation.dll (winerr " + std::to_string(GetLastError()) + ")";
            return false;
        }
        if (file_exists(join_path(single_dir, L"CoreADI64.dll"))) {
            LoadLibraryW(join_path(single_dir, L"CoreADI64.dll").c_str());
        }
        hAOSKit = LoadLibraryW(join_path(single_dir, L"AOSKit.dll").c_str());
        if (!hAOSKit) {
            err = "failed to load AOSKit.dll (winerr " + std::to_string(GetLastError()) + ")";
            return false;
        }
    } else {
        {
            std::lock_guard<std::mutex> slk(state_mu_);
            icloud_dir_used_ = classic.services_dir;
        }
        prepend_to_path(classic.services_dir);
        prepend_to_path(classic.aas_dir);
        SetDllDirectoryW(classic.aas_dir.c_str());
        SetCurrentDirectoryW(classic.aas_dir.c_str());

        hObjc = LoadLibraryW(join_path(classic.aas_dir, L"objc.dll").c_str());
        if (!hObjc) {
            err = "failed to load objc.dll (winerr " + std::to_string(GetLastError()) + ")";
            return false;
        }
        if (file_exists(join_path(classic.aas_dir, L"CoreFoundation.dll"))) {
            hCF = LoadLibraryW(join_path(classic.aas_dir, L"CoreFoundation.dll").c_str());
            (void)hCF;
        }
        hFoundation = LoadLibraryW(join_path(classic.aas_dir, L"Foundation.dll").c_str());
        if (!hFoundation) {
            err = "failed to load Foundation.dll (winerr " + std::to_string(GetLastError()) + ")";
            return false;
        }
        SetCurrentDirectoryW(classic.services_dir.c_str());
        hAOSKit = LoadLibraryW(join_path(classic.services_dir, L"AOSKit.dll").c_str());
        if (!hAOSKit) {
            err = "failed to load AOSKit.dll (winerr " + std::to_string(GetLastError()) + ")";
            return false;
        }
    }

    fn_objc_getClass_ = reinterpret_cast<void*>(GetProcAddress(hObjc, "objc_getClass"));
    fn_sel_registerName_ = reinterpret_cast<void*>(GetProcAddress(hObjc, "sel_registerName"));
    fn_objc_msgSend_ = reinterpret_cast<void*>(GetProcAddress(hObjc, "objc_msgSend"));

    if (!fn_objc_getClass_ || !fn_sel_registerName_ || !fn_objc_msgSend_) {
        err = "missing required exports in objc.dll";
        return false;
    }

    auto selReg = reinterpret_cast<SelRegisterNameFn>(fn_sel_registerName_);
    sel_alloc_ = selReg("alloc");
    sel_init_ = selReg("init");
    sel_drain_ = selReg("drain");
    sel_stringWithUTF8String_ = selReg("stringWithUTF8String:");
    sel_retrieveOTPHeadersForDSID_ = selReg("retrieveOTPHeadersForDSID:");
    sel_objectForKey_ = selReg("objectForKey:");
    sel_description_ = selReg("description");
    sel_UTF8String_ = selReg("UTF8String");

    // Resolve CoreFoundation string/dict helpers (needed for _GetDeviceId() and iCloud 15+ OTP).
    if (!hCF) {
        hCF = GetModuleHandleW(L"CoreFoundation.dll");
    }
    if (hCF) {
        fn_CFStringCreateWithCString_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFStringCreateWithCString"));
        fn_CFDictionaryGetValue_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFDictionaryGetValue"));
        fn_CFStringGetCString_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFStringGetCString"));
        fn_CFStringGetLength_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFStringGetLength"));
        fn_CFGetTypeID_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFGetTypeID"));
        fn_CFStringGetTypeID_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFStringGetTypeID"));
        fn_CFCopyDescription_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFCopyDescription"));
        fn_CFRelease_ =
            reinterpret_cast<void*>(GetProcAddress(hCF, "CFRelease"));
    }

    if (!fn_CFStringGetCString_ || !fn_CFStringGetLength_) {
        err = "missing required CoreFoundation string exports in CoreFoundation.dll";
        return false;
    }

    // Locate AOSKit internal device-identity functions (_GetDeviceId, _GetLocalUserUUID, Client-Info).
    AoskitIdentityFns id_fns{};
    if (!locate_aoskit_identity_fns(hAOSKit, id_fns, err)) {
        return false;
    }
    fn_getDeviceId_ = id_fns.get_device_id;
    fn_getLocalUserUUID_ = id_fns.get_local_user_uuid;
    fn_clientInfoOS_ = id_fns.client_info_os;
    fn_clientInfoApp_ = id_fns.client_info_app;

    // Check if Objective-C AOSUtilities is present (classic iCloud).
    // Otherwise fall back to CoreFoundation + AOSUtilities::copyOTPHeadersForDSID (iCloud 15+ Store).
    auto getClass = reinterpret_cast<ObjcGetClassFn>(fn_objc_getClass_);
    if (!getClass("AOSUtilities")) {
        fn_copyOTPHeadersForDSID_ = find_copy_otp_headers_fn(hAOSKit);
        if (!fn_copyOTPHeadersForDSID_ || !fn_CFStringCreateWithCString_ ||
            !fn_CFDictionaryGetValue_ || !fn_CFRelease_) {
            err = "AOSUtilities class and copyOTPHeadersForDSID entry point unavailable";
            return false;
        }
        use_cf_aoskit_ = true;
    }

    return true;
}

bool AnisetteProvider::compute_on_worker(AnisetteData& out, std::string& err) {
    auto getClass = reinterpret_cast<ObjcGetClassFn>(fn_objc_getClass_);
    auto msgSend0 = reinterpret_cast<MsgSend0Fn>(fn_objc_msgSend_);
    auto msgSend1 = reinterpret_cast<MsgSend1Fn>(fn_objc_msgSend_);
    auto msgSendCStr = reinterpret_cast<MsgSendCStrFn>(fn_objc_msgSend_);
    auto msgSendRetCStr = reinterpret_cast<MsgSendRetCStrFn>(fn_objc_msgSend_);
    auto msgSendVoid0 = reinterpret_cast<MsgSendVoid0Fn>(fn_objc_msgSend_);

    auto cfStrGetC = reinterpret_cast<CFStringGetCStringFn>(fn_CFStringGetCString_);
    auto cfStrLen = reinterpret_cast<CFStringGetLengthFn>(fn_CFStringGetLength_);
    auto cfGetTypeID = reinterpret_cast<CFGetTypeIDFn>(fn_CFGetTypeID_);
    auto cfStrGetTypeID = reinterpret_cast<CFStringGetTypeIDFn>(fn_CFStringGetTypeID_);
    auto cfCopyDesc = reinterpret_cast<CFCopyDescriptionFn>(fn_CFCopyDescription_);
    auto cfRelease = reinterpret_cast<CFReleaseFn>(fn_CFRelease_);

    auto cf_to_std_string = [&](void* cfObj) -> std::string {
        if (!cfObj || !cfStrGetC || !cfStrLen) return "";
        void* cfStr = cfObj;
        bool need_release = false;
        if (cfGetTypeID && cfStrGetTypeID && cfGetTypeID(cfObj) != cfStrGetTypeID()) {
            if (!cfCopyDesc) return "";
            cfStr = cfCopyDesc(cfObj);
            if (!cfStr) return "";
            need_release = true;
        }
        int64_t len = cfStrLen(cfStr);
        std::string buf;
        if (len > 0 && len <= 16384) {
            buf.assign(static_cast<size_t>(len * 4 + 1), '\0');
            if (cfStrGetC(cfStr, buf.data(), static_cast<int64_t>(buf.size()), kCFStringEncodingUTF8)) {
                buf.resize(std::strlen(buf.c_str()));
            } else {
                buf.clear();
            }
        }
        if (need_release && cfRelease) cfRelease(cfStr);
        return buf;
    };

    void* clsPool = getClass("NSAutoreleasePool");
    void* pool = nullptr;
    if (clsPool) {
        pool = msgSend0(clsPool, sel_alloc_);
        if (pool) {
            pool = msgSend0(pool, sel_init_);
        }
    }

    auto drain_pool = [&]() {
        if (pool) {
            msgSendVoid0(pool, sel_drain_);
            pool = nullptr;
        }
    };

    // Resolve and verify iCloud's own device identity once (never fall back to fake values).
    if (cached_device_id_.empty() || cached_local_user_uuid_.empty() || cached_client_info_.empty()) {
        if (!fn_getDeviceId_ || !fn_getLocalUserUUID_ || !fn_clientInfoOS_ || !fn_clientInfoApp_) {
            drain_pool();
            err = "AOSKit internal device identity functions are not available";
            return false;
        }

        void* cfDevId = nullptr;
        DWORD exc = 0;
        if (!seh_call_ret_cf0(reinterpret_cast<FnRetCF0>(fn_getDeviceId_), &cfDevId, &exc)) {
            drain_pool();
            err = "AOSKit _GetDeviceId() crashed (" + format_exc_hex(exc) + ")";
            return false;
        }
        std::string dev_id = cf_to_std_string(cfDevId);
        if (dev_id.empty()) {
            drain_pool();
            err = "AOSKit _GetDeviceId() returned empty value";
            return false;
        }

        char luBuf[512]{};
        if (!seh_call_ret_cstr0(reinterpret_cast<FnRetCStr0>(fn_getLocalUserUUID_), luBuf, sizeof(luBuf), &exc)) {
            drain_pool();
            err = "AOSKit _GetLocalUserUUID() crashed (" + format_exc_hex(exc) + ")";
            return false;
        }
        std::string local_uuid(luBuf);
        if (local_uuid.empty()) {
            drain_pool();
            err = "AOSKit _GetLocalUserUUID() returned empty value";
            return false;
        }

        char osBuf[512]{};
        if (!seh_call_ret_cstr0(reinterpret_cast<FnRetCStr0>(fn_clientInfoOS_), osBuf, sizeof(osBuf), &exc)) {
            drain_pool();
            err = "AOSKit Client-Info OS builder crashed (" + format_exc_hex(exc) + ")";
            return false;
        }
        char appBuf[512]{};
        if (!seh_call_ret_cstr0(reinterpret_cast<FnRetCStr0>(fn_clientInfoApp_), appBuf, sizeof(appBuf), &exc)) {
            drain_pool();
            err = "AOSKit Client-Info App builder crashed (" + format_exc_hex(exc) + ")";
            return false;
        }
        std::string os_part(osBuf);
        std::string app_part(appBuf);
        if (os_part.empty() || app_part.empty()) {
            drain_pool();
            err = "AOSKit Client-Info builder returned empty value";
            return false;
        }

        // Guard against compiler argument-evaluation order differences across AOSKit builds.
        if (os_part.rfind("com.apple.", 0) == 0 && app_part.find("Windows") != std::string::npos) {
            std::swap(os_part, app_part);
        }

        app_part = fix_client_info_app_version(std::move(app_part), icloud_version_);
        std::string client_info = "<PC> <" + os_part + "> <" + app_part + ">";

        cached_device_id_ = std::move(dev_id);
        cached_local_user_uuid_ = std::move(local_uuid);
        cached_client_info_ = std::move(client_info);
    }

    std::string md;
    std::string md_m;

    if (use_cf_aoskit_) {
        auto cfStrCreate = reinterpret_cast<CFStringCreateWithCStringFn>(fn_CFStringCreateWithCString_);
        auto cfDictGet = reinterpret_cast<CFDictionaryGetValueFn>(fn_CFDictionaryGetValue_);
        auto copyOTP = reinterpret_cast<CopyOTPHeadersForDSIDFn>(fn_copyOTPHeadersForDSID_);

        void* dsid = cfStrCreate(nullptr, "-2", kCFStringEncodingUTF8);
        void* headers = dsid ? copyOTP(dsid) : nullptr;
        if (dsid) cfRelease(dsid);

        if (!headers) {
            drain_pool();
            err = "AOSKit copyOTPHeadersForDSID returned null (ensure iCloud for Windows is signed in)";
            return false;
        }

        void* keyMD = cfStrCreate(nullptr, "X-Apple-MD", kCFStringEncodingUTF8);
        void* keyMDM = cfStrCreate(nullptr, "X-Apple-MD-M", kCFStringEncodingUTF8);
        void* valMD = keyMD ? cfDictGet(headers, keyMD) : nullptr;
        void* valMDM = keyMDM ? cfDictGet(headers, keyMDM) : nullptr;

        md = cf_to_std_string(valMD);
        md_m = cf_to_std_string(valMDM);

        if (keyMD) cfRelease(keyMD);
        if (keyMDM) cfRelease(keyMDM);
        cfRelease(headers);
    } else {
        void* clsNSString = getClass("NSString");
        void* clsAOSUtilities = getClass("AOSUtilities");

        if (!clsPool || !clsNSString || !clsAOSUtilities) {
            drain_pool();
            err = "required Objective-C classes unavailable";
            return false;
        }

        auto make_nsstring = [&](const char* s) -> void* {
            return msgSendCStr(clsNSString, sel_stringWithUTF8String_, s);
        };

        auto obj_to_utf8 = [&](void* obj) -> std::string {
            if (!obj) return "";
            void* desc = msgSend0(obj, sel_description_);
            if (!desc) return "";
            const char* cstr = msgSendRetCStr(desc, sel_UTF8String_);
            return cstr ? std::string(cstr) : std::string();
        };

        void* dsid = make_nsstring("-2");
        void* headers = msgSend1(clsAOSUtilities, sel_retrieveOTPHeadersForDSID_, dsid);
        if (!headers) {
            drain_pool();
            err = "AOSUtilities returned no OTP headers (ensure iCloud for Windows is signed in)";
            return false;
        }

        void* keyMD = make_nsstring("X-Apple-MD");
        void* keyMDM = make_nsstring("X-Apple-MD-M");
        void* valMD = msgSend1(headers, sel_objectForKey_, keyMD);
        void* valMDM = msgSend1(headers, sel_objectForKey_, keyMDM);

        md = obj_to_utf8(valMD);
        md_m = obj_to_utf8(valMDM);
    }

    drain_pool();

    if (md.empty() || md_m.empty() ||
        cached_device_id_.empty() || cached_local_user_uuid_.empty() || cached_client_info_.empty()) {
        err = "incomplete anisette data from AOSUtilities";
        return false;
    }

    out.md = std::move(md);
    out.md_m = std::move(md_m);
    out.md_lu = cached_local_user_uuid_;
    out.md_rinfo = kRoutingInfo;
    out.device_id = cached_device_id_;
    out.client_info = cached_client_info_;
    out.client_time = format_utc_now();
    out.timezone = get_local_timezone_abbr();
    out.locale = get_apple_locale();
    return true;
}

std::string AnisetteProvider::to_json(const AnisetteData& d) {
    std::string s;
    s.reserve(512);
    s += "{";
    s += "\"X-Apple-I-MD\":\"" + json_escape(d.md) + "\",";
    s += "\"X-Apple-I-MD-M\":\"" + json_escape(d.md_m) + "\",";
    s += "\"X-Apple-I-MD-LU\":\"" + json_escape(d.md_lu) + "\",";
    s += "\"X-Apple-I-MD-RINFO\":\"" + json_escape(d.md_rinfo) + "\",";
    s += "\"X-Mme-Device-Id\":\"" + json_escape(d.device_id) + "\",";
    s += "\"X-Mme-Client-Info\":\"" + json_escape(d.client_info) + "\",";
    s += "\"X-Apple-I-Client-Time\":\"" + json_escape(d.client_time) + "\",";
    s += "\"X-Apple-I-TimeZone\":\"" + json_escape(d.timezone) + "\",";
    s += "\"X-Apple-Locale\":\"" + json_escape(d.locale) + "\"";
    s += "}";
    return s;
}

std::string AnisetteProvider::client_info_device_part(const std::string& client_info) {
    size_t open1 = client_info.find('<');
    if (open1 == std::string::npos) return client_info;
    size_t close1 = client_info.find('>', open1 + 1);
    if (close1 == std::string::npos) return client_info;
    size_t open2 = client_info.find('<', close1 + 1);
    if (open2 != std::string::npos) {
        size_t close2 = client_info.find('>', open2 + 1);
        if (close2 != std::string::npos) {
            return client_info.substr(open1, close2 - open1 + 1);
        }
    }
    return client_info.substr(open1, close1 - open1 + 1);
}

} // namespace anisette
