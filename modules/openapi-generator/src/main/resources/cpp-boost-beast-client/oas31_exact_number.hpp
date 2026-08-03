// ============================================================================
// oas31_exact_number.hpp — Wave-1 exact JSON Number domain (ADR D1).
//
// A finite base-10 arbitrary-precision decimal: value = mantissa * 10^exponent10.
// Fully independent of Boost.JSON `double`. Keeps 1 == 1.0 == 1e0 under ==/compare.
// multipleOf is an exact divmod with zero remainder — never floating division.
//
// HEADER-ONLY. Built under -Werror with g++ -std=c++17.
// Skeletal slice: core arithmetic implemented; semantic corpus is a later slice.
// ============================================================================
#ifndef OAS31_EXACT_NUMBER_HPP_
#define OAS31_EXACT_NUMBER_HPP_

#include <boost/multiprecision/cpp_int.hpp>

#include <algorithm>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>

namespace oas31 {

/// Finite base-10 arbitrary-precision decimal: value = mantissa * 10^exponent10.
class ExactNumber {
public:
    using Integer = boost::multiprecision::cpp_int;

    ExactNumber() = default;

    ExactNumber(Integer mantissa, std::int32_t exponent10)
        : mantissa_(std::move(mantissa)), exponent10_(exponent10) {}

    /// Parse a raw JSON numeric lexeme (e.g. "-1.5e+3", "0.0001", "-0", "1e400") exactly.
    /// Does NOT go through Boost.JSON, so the lexeme is preserved verbatim.
    static ExactNumber parseLexeme(std::string const& lexeme);

    /// Exact for any 64-bit integer.
    static ExactNumber fromInt(std::int64_t v) { return ExactNumber(Integer(v), 0); }
    static ExactNumber fromUint(std::uint64_t v) { return ExactNumber(Integer(v), 0); }

    /// Exact ONLY for values in double's exact representable set; documented
    /// as lossy for any other double (used only as a fallback when no lexeme).
    static ExactNumber fromDouble(double v);

    Integer const& mantissa() const { return mantissa_; }
    std::int32_t exponent10() const { return exponent10_; }

    /// Three-way compare that normalizes exponents across the two values.
    int compare(ExactNumber const& other) const;

    bool isZero() const { return mantissa_ == 0; }

    ExactNumber add(ExactNumber const& other) const;
    ExactNumber mul(ExactNumber const& other) const;

    /// Exact divmod. Sets quotient and remainder such that:
    ///   (*this) == quotient*divisor + remainder  and  remainder == 0 <=> divisible.
    /// Throws std::domain_error when divisor is zero (a JSON Schema `multipleOf`
    /// of 0 is not a positive number and must be rejected at the CALLER level;
    /// zero divisibility is refused here so no silent modulo ever occurs).
    void divmod(ExactNumber const& divisor, ExactNumber& quotient,
                ExactNumber& remainder) const;

    /// True when the value is a mathematical integer (exact), judged by VALUE not
    /// spelling: 1.0, 1e0, 100e-2 and -0.0 are all integers because they equal a
    /// whole number; 1e-1 is not.
    bool isInteger() const;

    bool operator==(ExactNumber const& o) const { return compare(o) == 0; }
    bool operator!=(ExactNumber const& o) const { return compare(o) != 0; }
    bool operator<(ExactNumber const& o) const { return compare(o) < 0; }

    std::string toString() const;

private:
    static Integer ipow10(unsigned n);

    Integer     mantissa_;
    std::int32_t exponent10_ = 0;
};

// ---------------------------------------------------------------------------
// Implementation (header-only)
// ---------------------------------------------------------------------------

inline ExactNumber::Integer ExactNumber::ipow10(unsigned n) {
    Integer r = 1;
    for (unsigned k = 0; k < n; ++k) r *= 10;
    return r;
}

inline ExactNumber ExactNumber::parseLexeme(std::string const& s) {
    std::size_t i = 0;
    bool neg = false;
    if (i < s.size() && s[i] == '-') { neg = true; ++i; }

    bool hasIntDigit = false;
    Integer intpart(0);
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
        hasIntDigit = true;
        intpart = intpart * 10 + static_cast<int>(s[i] - '0');
        ++i;
    }
    if (!hasIntDigit) {
        throw std::domain_error("ExactNumber::parseLexeme: missing integer digit");
    }

    std::string frac;
    if (i < s.size() && s[i] == '.') {
        ++i;
        bool hasFracDigit = false;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
            hasFracDigit = true;
            frac.push_back(s[i]);
            ++i;
        }
        if (!hasFracDigit) {
            throw std::domain_error("ExactNumber::parseLexeme: missing fraction digit");
        }
    }

    long long e = 0;
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        bool eneg = false;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) {
            eneg = (s[i] == '-');
            ++i;
        }
        bool hasExpDigit = false;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
            hasExpDigit = true;
            // Guard against long long overflow on pathological (huge-digit) exponents;
            // anything beyond int32 range is refused anyway, so bail out early.
            if (e > 3000000000LL) {
                throw std::out_of_range(
                    "ExactNumber::parseLexeme: exponent out of int32 range");
            }
            e = e * 10 + static_cast<int>(s[i] - '0');
            ++i;
        }
        if (!hasExpDigit) {
            throw std::domain_error("ExactNumber::parseLexeme: missing exponent digit");
        }
        if (eneg) e = -e;
    }
    if (i != s.size()) {
        throw std::domain_error("ExactNumber::parseLexeme: trailing characters after lexeme");
    }

    // Total base-10 exponent after the fraction shift; must fit in int32.
    const long long totalExp =
        e - static_cast<long long>(frac.size());
    if (totalExp > static_cast<long long>(std::numeric_limits<std::int32_t>::max()) ||
        totalExp < static_cast<long long>(std::numeric_limits<std::int32_t>::min())) {
        throw std::out_of_range("ExactNumber::parseLexeme: exponent out of int32 range");
    }

    Integer mantissa = intpart;
    for (char c : frac) mantissa = mantissa * 10 + static_cast<int>(c - '0');
    if (neg) mantissa = -mantissa;
    return ExactNumber(std::move(mantissa), static_cast<std::int32_t>(totalExp));
}

inline ExactNumber ExactNumber::fromDouble(double v) {
    // Exact only when v is integral and fits; otherwise documented lossy.
    if (v != static_cast<long long>(v)) {
        return ExactNumber(static_cast<long long>(v * 1e12), -12);
    }
    return ExactNumber(Integer(static_cast<long long>(v)), 0);
}

inline int ExactNumber::compare(ExactNumber const& o) const {
    if (isZero() && o.isZero()) return 0;
    const bool n1 = mantissa_ < 0;
    const bool n2 = o.mantissa_ < 0;
    if (n1 != n2) return n1 ? -1 : 1;

    Integer a = n1 ? -mantissa_ : mantissa_;
    Integer b = n2 ? -o.mantissa_ : o.mantissa_;
    // Scale both to the SMALLER exponent (multiplying is always exact).
    const std::int32_t e = std::min(exponent10_, o.exponent10_);
    a *= ipow10(static_cast<unsigned>(exponent10_ - e));
    b *= ipow10(static_cast<unsigned>(o.exponent10_ - e));
    int c;
    if (a < b) c = -1;
    else if (a > b) c = 1;
    else c = 0;
    return n1 ? -c : c;
}

inline ExactNumber ExactNumber::add(ExactNumber const& o) const {
    const std::int32_t e = std::min(exponent10_, o.exponent10_);
    Integer a = mantissa_ * ipow10(static_cast<unsigned>(exponent10_ - e));
    Integer b = o.mantissa_ * ipow10(static_cast<unsigned>(o.exponent10_ - e));
    return ExactNumber(a + b, e);
}

inline ExactNumber ExactNumber::mul(ExactNumber const& o) const {
    return ExactNumber(mantissa_ * o.mantissa_, exponent10_ + o.exponent10_);
}

inline bool ExactNumber::isInteger() const {
    if (exponent10_ >= 0) return true;          // value = mantissa * 10^e, whole
    const Integer div = ipow10(static_cast<unsigned>(-exponent10_));
    return mantissa_ % div == 0;                // whole iff 10^-e divides mantissa exactly
}

inline void ExactNumber::divmod(ExactNumber const& divisor, ExactNumber& quotient,
                                ExactNumber& remainder) const {
    if (divisor.isZero()) {
        throw std::domain_error("ExactNumber::divmod: division by zero");
    }
    const std::int32_t e = std::min(exponent10_, divisor.exponent10_);
    Integer N = mantissa_ * ipow10(static_cast<unsigned>(exponent10_ - e));
    Integer D = divisor.mantissa_ * ipow10(static_cast<unsigned>(divisor.exponent10_ - e));
    Integer Q = N / D;
    Integer R = N % D;
    quotient = ExactNumber(Q, 0);
    remainder = ExactNumber(R, e);
}

inline std::string ExactNumber::toString() const {
    if (isZero()) return "0";
    std::string m = mantissa_.str();
    if (exponent10_ != 0) {
        m += "e";
        if (exponent10_ > 0) m += "+";
        m += std::to_string(exponent10_);
    }
    return m;
}

// ---------------------------------------------------------------------------
// multipleOf / divisibility helper (exact).
//
// NOTE on non-positive `multipleOf` (JSON Schema: multipleOf must be > 0): the
// class-level divmod refuses a ZERO divisor by throwing. A NEGATIVE divisor is
// mathematically well-defined here (divmod remains exact and remainder==0 iff
// divisible), but a JSON Schema `multipleOf` of <= 0 is INVALID and must be
// rejected at the CALLER (IR/evaluator) level, which owns the schema-validity
// decision. This helper exists so the caller rejects it centrally with zero
// ambiguity, never letting a non-positive value reach divmod as if valid.
inline bool isPositiveMultipleOf(ExactNumber const& m) {
    return !m.isZero() && m.mantissa() > 0;
}

} // namespace oas31

#endif // OAS31_EXACT_NUMBER_HPP_
