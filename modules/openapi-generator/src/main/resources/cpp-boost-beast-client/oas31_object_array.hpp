// ============================================================================
// oas31_object_array.hpp — Wave-2 instance-lexeme capture at container depth
// (exact-number propagation into container-member children).
//
// Wave-1's captureLeadingNumberLexeme (oas-compliance/) only captured the RAW
// numeric lexeme when the WHOLE instance is a scalar root. Numbers nested inside
// objects/arrays (deep const/enum/not/uniqueItems members) degenerated to the
// Boost.JSON value kind (double), which is lossy for 1e400, 0.30000000000000004,
// 2^53+1, etc. The Wave-1 complete report records this as an OPEN item.
//
// This header FREEZES the container-depth lexeme capture contract:
//   * InstanceLexemeTable — maps a canonical instance path (JSON-pointer form,
//     "" root, "/foo", "/foo/0", "/foo~1bar") to the verbatim raw number lexeme.
//   * oas31::captureInstanceLexemes(payload, table) — a pure lexical JSON walk
//     over the payload STRING (no float), recording every JSON number token at
//     its canonical path. It deliberately mirrors the shape boost::json::parse
//     produces, but keyed on the raw bytes so ExactNumber::parseLexeme stays
//     exact even several container levels deep.
//   * oas31::jsonPointerEscape — shared escaping so RawInstance::atMember /
//     atIndex build the IDENTICAL canonical path the scanner used. Path equality
//     is what makes a nested number's lexeme discoverable.
//
// The evaluator (oas31_validator.hpp) attaches a table to RawInstance (optional);
// when absent, exactness degrades exactly as in Wave-1 (honest fallback).
//
// HEADER-ONLY. Built under -Werror with g++ -std=c++17.
// ============================================================================
#ifndef OAS31_OBJECT_ARRAY_HPP_
#define OAS31_OBJECT_ARRAY_HPP_

#include <cstddef>
#include <map>
#include <string>
#include <vector>

namespace oas31 {

/// JSON-pointer escaping shared by the scanner (capture) and by
/// RawInstance::atMember/atIndex (lookup). '~' -> "~0", '/' -> "~1" per
/// RFC 6901, so a member name can never be confused with a path separator.
inline std::string jsonPointerEscape(std::string const& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '~') { out += "~0"; }
        else if (c == '/') { out += "~1"; }
        else { out.push_back(c); }
    }
    return out;
}

/// Optional per-instance capture of raw numeric lexemes keyed by canonical
/// instance path. Populated by captureInstanceLexemes; consumed by
/// RawInstance::asExactNumber (through RawInstance::lexemeAt).
struct InstanceLexemeTable {
    std::map<std::string, std::string> entries;  // canonical path -> raw lexeme

    /// Return the raw lexeme for a canonical path, or nullptr when the path is
    /// not a number in the capture (or the capture has no entry).
    std::string const* lexemeAt(std::string const& path) const {
        auto it = entries.find(path);
        return it == entries.end() ? nullptr : &it->second;
    }
};

namespace detail {

/// Decode one JSON string token starting at `s[i] == '"'`. Returns the decoded
/// string (escapes resolved); advances `i` past the closing quote. Best-effort:
/// on any malformed escape the rest is copied verbatim (never out-of-bounds).
inline std::string readJsonString(std::string const& s, std::size_t& i) {
    std::string out;
    if (i >= s.size() || s[i] != '"') return out;
    ++i;  // opening quote
    while (i < s.size()) {
        char const c = s[i];
        if (c == '"') { ++i; return out; }
        if (c == '\\') {
            ++i;
            if (i >= s.size()) { out.push_back('\\'); return out; }
            char const e = s[i];
            switch (e) {
                case '"':  out.push_back('"');  ++i; break;
                case '\\': out.push_back('\\'); ++i; break;
                case '/':  out.push_back('/');  ++i; break;
                case 'b':  out.push_back('\b'); ++i; break;
                case 'f':  out.push_back('\f'); ++i; break;
                case 'n':  out.push_back('\n'); ++i; break;
                case 'r':  out.push_back('\r'); ++i; break;
                case 't':  out.push_back('\t'); ++i; break;
                case 'u': {
                    ++i;
                    if (i + 4 <= s.size()) {
                        unsigned cp = 0;
                        bool ok = true;
                        for (unsigned k = 0; k < 4; ++k) {
                            char const h = s[i + k];
                            unsigned v;
                            if (h >= '0' && h <= '9') v = static_cast<unsigned>(h - '0');
                            else if (h >= 'a' && h <= 'f') v = static_cast<unsigned>(h - 'a' + 10);
                            else if (h >= 'A' && h <= 'F') v = static_cast<unsigned>(h - 'A' + 10);
                            else { ok = false; break; }
                            cp = cp * 16u + v;
                        }
                        if (ok) {
                            i += 4;
                            if (cp < 0x80u) out.push_back(static_cast<char>(cp));
                            else if (cp < 0x800u) {
                                out.push_back(static_cast<char>(0xC0u | (cp >> 6u)));
                                out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
                            } else {
                                out.push_back(static_cast<char>(0xE0u | (cp >> 12u)));
                                out.push_back(static_cast<char>(0x80u | ((cp >> 6u) & 0x3Fu)));
                                out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
                            }
                        } else {
                            out.push_back('u');
                        }
                    } else {
                        out.push_back('u');
                    }
                    break;
                }
                default:
                    out.push_back('\\');
                    out.push_back(e);
                    ++i;
                    break;
            }
        } else {
            out.push_back(c);
            ++i;
        }
    }
    return out;
}

/// Read a JSON numeric token starting at `s[i]` (which must be '-' or a digit).
/// Returns the verbatim lexeme; advances `i` past the last number character.
inline std::string readJsonNumber(std::string const& s, std::size_t& i) {
    std::size_t const start = i;
    if (i < s.size() && s[i] == '-') ++i;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    if (i < s.size() && s[i] == '.') {
        ++i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    }
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) ++i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    }
    return s.substr(start, i - start);
}

} // namespace detail

/// Capture every JSON number lexeme in `payload` into `table`, keyed by the
/// canonical instance path (JSON-pointer form). Pure lexical walk — no float is
/// ever constructed, so 1e400 / -0 / 2^53+1 survive verbatim at ANY depth.
///
/// Path construction mirrors RawInstance::atMember/atIndex (jsonPointerEscape),
/// so a number reached through `instance.atMember("foo").atIndex(0)` is found
/// under "/foo/0". Values that carry no number (strings/literals/malformed
/// segments) never create entries; the evaluator falls back to the typed value
/// kind for any path without an entry (honest Wave-1-equivalent degradation).
inline void captureInstanceLexemes(std::string const& payload,
                                   InstanceLexemeTable& table) {
    struct Frame {
        bool        isArray;
        std::size_t index;     // array element cursor
        std::string pendingKey; // object member key (decoded, unescaped)
    };
    std::vector<Frame> stack;

    std::size_t i = 0;
    std::string const ws = " \t\n\r";
    auto skipWs = [&]() {
        while (i < payload.size() && ws.find(payload[i]) != std::string::npos) ++i;
    };

    while (i < payload.size()) {
        skipWs();
        if (i >= payload.size()) break;
        char const c = payload[i];

        if (c == '{') {
            stack.push_back(Frame{false, 0, std::string()});
            ++i;
        } else if (c == '[') {
            stack.push_back(Frame{true, 0, std::string()});
            ++i;
        } else if (c == '}') {
            if (!stack.empty()) stack.pop_back();
            ++i;
        } else if (c == ']') {
            if (!stack.empty()) stack.pop_back();
            ++i;
        } else if (c == ',') {
            if (!stack.empty() && stack.back().isArray) stack.back().index++;
            ++i;
        } else if (c == ':') {
            ++i;  // value follows; pendingKey (if any) stays valid
        } else if (c == '"') {
            std::string const tok = detail::readJsonString(payload, i);
            // A string is a member KEY only when the next non-ws char is ':'.
            // String VALUES are ignored (they never precede numbers as a key).
            skipWs();
            if (i < payload.size() && payload[i] == ':' && !stack.empty()) {
                stack.back().pendingKey = tok;
            }
        } else if (c == 't' || c == 'f' || c == 'n') {
            // literal true / false / null — skip the word, no lexeme entry.
            if (c == 't' && payload.compare(i, 4, "true") == 0) i += 4;
            else if (c == 'f' && payload.compare(i, 5, "false") == 0) i += 5;
            else if (c == 'n' && payload.compare(i, 4, "null") == 0) i += 4;
            else ++i;  // malformed (best-effort: never loop forever)
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            std::string const lexeme = detail::readJsonNumber(payload, i);
            std::string path;
            for (Frame const& f : stack) {
                path.push_back('/');
                path.append(f.isArray ? std::to_string(f.index)
                                      : jsonPointerEscape(f.pendingKey));
            }
            table.entries[path] = lexeme;
        } else {
            ++i;  // unknown byte (best-effort forward progress)
        }
    }
}

} // namespace oas31

#endif // OAS31_OBJECT_ARRAY_HPP_
