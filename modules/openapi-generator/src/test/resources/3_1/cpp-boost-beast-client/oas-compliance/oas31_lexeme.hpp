// ===========================================================================
// oas31_lexeme.hpp — RAW JSON number-lexeme tokenizer (Wave-1 exact-number
// path).  Owned by the `harness` agent (oas-compliance/).
//
// WHY THIS EXISTS
//   The ADR/contract finding is that `boost::json::parse` DESTROYS the numeric
//   lexeme: every JSON number is canonicalized into one of int64 / uint64 /
//   double_ and the original spelling is discarded.  That loses 1e400,
//   -0, 2^53+1, 0.30000000000000004-style precision, etc.  `ExactNumber` is
//   exact by construction, but it must receive the ORIGINAL lexeme.
//
//   This header captures the raw JSON numeric lexeme directly from the payload
//   STRING, BEFORE boost::json ever sees it, so `RawInstance::numericLexeme`
//   (and therefore `RawInstance::asExactNumber()`) is exact.  It is a pure
//   lexical scan — no float involved — following the JSON number grammar:
//     -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?
//
//   The typed Boost.JSON value is parsed in parallel purely as the transport /
//   representability gate (the M-profile "typed view"); exact-number validity
//   never relies on it.
//
//   HEADER-ONLY.  Built under -Werror with g++ -std=c++17 (namespace oas31).
// ===========================================================================
#ifndef OAS31_LEXEME_HPP_
#define OAS31_LEXEME_HPP_

#include <cstddef>
#include <string>

namespace oas31 {

/// Capture the raw JSON numeric lexeme that STARTS `s` (after trivial leading
/// whitespace), if the leading token is a JSON number.
///
/// On success returns true and sets `out` to the exact verbatim spelling of the
/// number (e.g. "-0", "1.0", "100e-2", "1e400", "1180591620717411303424").
/// On any non-number token (or a malformed number like ".5" / "1e" / trailing
/// junk) returns false and leaves `out` unchanged/empty.  We only look at the
/// leading token: the slice's numeric/boolean cases are scalar-rooted (the
/// WHOLE raw instance is the number or boolean), which is exactly where
/// exact-number correctness matters most (const/enum/multipleOf/bounds/type).
inline bool captureLeadingNumberLexeme(std::string const& s, std::string& out) {
    std::size_t i = 0;
    // 1. skip leading whitespace
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r'))
        ++i;
    std::size_t const start = i;

    // 2. optional sign
    if (i < s.size() && (s[i] == '-' || s[i] == '+')) ++i;

    // 3. integer part — JSON REQUIRES at least one digit here ("12", never ".5")
    std::size_t const intStart = i;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    if (i == intStart) { out.clear(); return false; }  // not a number

    // 4. optional fraction — "." MUST be followed by >=1 digit ("1.")
    if (i < s.size() && s[i] == '.') {
        ++i;
        std::size_t const frStart = i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
        if (i == frStart) { out.clear(); return false; }  // "1." is not JSON
    }

    // 5. optional exponent — [eE][+-]? digits; MUST have >=1 digit
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) ++i;
        std::size_t const exStart = i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
        if (i == exStart) { out.clear(); return false; }  // "1e" / "1e+" malformed
    }

    out.assign(s, start, i - start);
    return true;
}

} // namespace oas31

#endif // OAS31_LEXEME_HPP_
