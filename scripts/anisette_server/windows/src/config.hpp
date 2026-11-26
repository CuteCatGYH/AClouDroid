#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace anisette {

struct Config {
    std::string token;
};

// Base64 standard encoding (with '=' padding).
std::string base64_encode(const uint8_t* data, size_t len);
std::string base64_encode(const std::string& data);

// Base64url encoding without padding.
std::string base64url_encode(const uint8_t* data, size_t len);

// JSON string escaping (returns inner content without surrounding quotes).
std::string json_escape(const std::string& s);

// Constant-time string equality check.
bool constant_time_equals(const std::string& a, const std::string& b);

// Generate 32 CSPRNG bytes encoded as 43-char base64url without padding.
std::string generate_token();

// Resolve config.json path (ACLOUDDROID_ANISETTE_HOME override, else %APPDATA%\AClouDroidAnisette\config.json)
std::wstring get_config_path();

// Load or initialize config.json. If regenerate_token is true, replaces token and saves.
// Returns true on success; on failure populates err.
bool load_or_create_config(Config& out, bool regenerate_token, std::string& err);

} // namespace anisette
