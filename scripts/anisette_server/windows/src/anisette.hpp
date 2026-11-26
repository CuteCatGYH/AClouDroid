#pragma once

#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>

namespace anisette {

struct AnisetteData {
    std::string md;          // X-Apple-I-MD
    std::string md_m;        // X-Apple-I-MD-M
    std::string md_lu;       // X-Apple-I-MD-LU
    std::string md_rinfo;    // X-Apple-I-MD-RINFO
    std::string device_id;   // X-Mme-Device-Id
    std::string client_info; // X-Mme-Client-Info
    std::string client_time; // X-Apple-I-Client-Time
    std::string timezone;    // X-Apple-I-TimeZone
    std::string locale;      // X-Apple-Locale
};

// Serializes all iCloud/ObjC runtime work onto one dedicated worker thread,
// wrapping every computation in an NSAutoreleasePool.
class AnisetteProvider {
public:
    explicit AnisetteProvider(std::wstring icloud_dir_override);
    ~AnisetteProvider();

    AnisetteProvider(const AnisetteProvider&) = delete;
    AnisetteProvider& operator=(const AnisetteProvider&) = delete;

    // Initializes worker thread and attempts to load iCloud DLLs.
    // Returns true if DLLs loaded successfully; false with init_error() otherwise.
    bool init();

    bool is_ready() const;
    std::string init_error() const;
    std::wstring icloud_dir_used() const;

    // Computes fresh anisette headers synchronously on the dedicated worker thread.
    // Returns true on success; on failure populates err with a short reason.
    bool fetch(AnisetteData& out, std::string& err);

    // Formats AnisetteData as a flat JSON object of string values.
    static std::string to_json(const AnisetteData& data);

    // Extracts the device and OS parts of Client-Info (e.g. "<PC> <Windows; ...>").
    static std::string client_info_device_part(const std::string& client_info);

private:
    struct Request {
        AnisetteData* out = nullptr;
        std::string* err = nullptr;
        bool ok = false;
        bool done = false;
    };

    void worker_loop();
    bool load_icloud_dlls_on_worker(std::string& err);
    bool compute_on_worker(AnisetteData& out, std::string& err);

    std::wstring icloud_dir_override_;
    std::wstring icloud_dir_used_;
    std::string icloud_version_;

    // Cached device identity values obtained from AOSKit.dll (worker thread only).
    std::string cached_device_id_;
    std::string cached_local_user_uuid_;
    std::string cached_client_info_;

    // ObjC runtime function pointers (used only on worker thread).
    void* fn_objc_getClass_ = nullptr;
    void* fn_sel_registerName_ = nullptr;
    void* fn_objc_msgSend_ = nullptr;
    void* sel_alloc_ = nullptr;
    void* sel_init_ = nullptr;
    void* sel_drain_ = nullptr;
    void* sel_stringWithUTF8String_ = nullptr;
    void* sel_retrieveOTPHeadersForDSID_ = nullptr;
    void* sel_objectForKey_ = nullptr;
    void* sel_description_ = nullptr;
    void* sel_UTF8String_ = nullptr;

    // CoreFoundation + C/C++ AOSKit entry points (worker thread only).
    bool use_cf_aoskit_ = false;
    void* fn_copyOTPHeadersForDSID_ = nullptr;
    void* fn_getDeviceId_ = nullptr;
    void* fn_getLocalUserUUID_ = nullptr;
    void* fn_clientInfoOS_ = nullptr;
    void* fn_clientInfoApp_ = nullptr;
    void* fn_CFStringCreateWithCString_ = nullptr;
    void* fn_CFDictionaryGetValue_ = nullptr;
    void* fn_CFStringGetCString_ = nullptr;
    void* fn_CFStringGetLength_ = nullptr;
    void* fn_CFGetTypeID_ = nullptr;
    void* fn_CFStringGetTypeID_ = nullptr;
    void* fn_CFCopyDescription_ = nullptr;
    void* fn_CFRelease_ = nullptr;

    mutable std::mutex state_mu_;
    bool ready_ = false;
    std::string init_err_;

    std::mutex call_mu_;      // Ensures one caller at a time
    std::mutex queue_mu_;
    std::condition_variable cv_req_;
    std::condition_variable cv_done_;
    bool stop_ = false;
    bool init_done_ = false;
    Request* current_req_ = nullptr;
    std::thread worker_;
};

} // namespace anisette
