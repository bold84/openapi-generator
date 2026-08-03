// ============================================================================
// oas31_exact_number_test.cpp — focused unit test for the Wave-1 exact JSON
// Number domain (ADR D1). Owned by the exact-lib agent.
//
// Asserts EVERY CRITICAL case from the slice contract:
//   1 == 1.0 == 1e0 == 100e-2 ; negative zero ; very large coefficients /
//   exponents ; decimal normalization + trailing zeros ; exact multipleOf
//   0.3 / divisor 0.1 -> divisible ; reject non-positive multipleOf ;
//   integer-classification by VALUE not spelling (1.0 IS an integer) ;
//   const/enum equality across spellings ; overflow/size-limits with explicit
//   REFUSAL (never silent approximation).
//
// Build (repo root):
//   g++ -std=c++17 -Wall -Wextra -Werror -I/opt/homebrew/include \
//       -Imodules/openapi-generator/src/main/resources/cpp-boost-beast-client \
//       modules/openapi-generator/src/test/resources/3_1/cpp-boost-beast-client/oas31-exact-number/oas31_exact_number_test.cpp \
//       -o /tmp/oas31_exact_number_test && /tmp/oas31_exact_number_test
// ============================================================================
#include "oas31_exact_number.hpp"

#include <cstdio>
#include <exception>
#include <stdexcept>
#include <string>

using oas31::ExactNumber;

namespace {

int g_pass = 0;
int g_fail = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (cond) {                                                     \
            ++g_pass;                                                   \
        } else {                                                        \
            ++g_fail;                                                   \
            std::printf("  FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); \
        }                                                               \
    } while (0)

// True iff parseLexeme throws (explicit refusal, never silent anyway).
bool parseThrows(std::string const& s) {
    try {
        ExactNumber n = ExactNumber::parseLexeme(s);
        (void)n;
        return false;
    } catch (std::exception const&) {
        return true;
    }
}

// Exact divisibility oracle for the multipleOf checks.
bool isMultiple(ExactNumber const& value, ExactNumber const& m) {
    ExactNumber q, r;
    value.divmod(m, q, r);
    return r.isZero();
}

// divmod-with-zero-divisor must refuse.
bool divmodZeroThrows(ExactNumber const& value) {
    try {
        ExactNumber q, r;
        value.divmod(ExactNumber::parseLexeme("0"), q, r);
        (void)q; (void)r;
        return false;
    } catch (std::exception const&) {
        return true;
    }
}

} // namespace

int main() {
    using oas31::isPositiveMultipleOf;

    // --- CRITICAL 1: 1 == 1.0 == 1e0 == 100e-2 (equality across spellings) ---
    {
        ExactNumber a = ExactNumber::parseLexeme("1");
        ExactNumber b = ExactNumber::parseLexeme("1.0");
        ExactNumber c = ExactNumber::parseLexeme("1e0");
        ExactNumber d = ExactNumber::parseLexeme("100e-2");
        ExactNumber e = ExactNumber::parseLexeme("1.00");
        CHECK(a == b);
        CHECK(a == c);
        CHECK(a == d);
        CHECK(a == e);
        CHECK(a.compare(b) == 0);
        CHECK(a.compare(c) == 0);
        CHECK(b < ExactNumber::parseLexeme("1.001"));
        CHECK(!(a != b));
        // fromInt/fromDouble agree with lexeme for the exact integral set.
        CHECK(a == ExactNumber::fromInt(1));
        CHECK(ExactNumber::fromUint(1ULL) == a);
        CHECK(ExactNumber::fromDouble(1.0) == a);
    }

    // --- CRITICAL 2: negative zero ---
    {
        ExactNumber nz = ExactNumber::parseLexeme("-0");
        CHECK(nz.isZero());
        CHECK(nz == ExactNumber::parseLexeme("0"));
        CHECK(nz == ExactNumber::parseLexeme("-0.0"));
        CHECK(nz == ExactNumber::parseLexeme("0e-5"));
        CHECK(nz.compare(ExactNumber::parseLexeme("0")) == 0);
        CHECK(ExactNumber::parseLexeme("-0.000") == ExactNumber::fromInt(0));
    }

    // --- CRITICAL 3: very large coefficients / exponents (no double loss) ---
    {
        ExactNumber big = ExactNumber::parseLexeme("1e400");
        CHECK(big.mantissa() == 1);
        CHECK(big.exponent10() == 400);
        // 9.999999999999999999e+999 is strictly less than 1e1000 (exact compare).
        CHECK(ExactNumber::parseLexeme("9.999999999999999999e+999")
                  < ExactNumber::parseLexeme("1e1000"));
        // Sub-double fractional precision is preserved.
        CHECK(ExactNumber::parseLexeme("0.1000000000000000000000000000001") !=
              ExactNumber::parseLexeme("0.1"));
        // 1e400 pretend-equal to 1e400 regardless of spelling.
        CHECK(ExactNumber::parseLexeme("1e400") == ExactNumber::parseLexeme("1000e397"));
        CHECK(ExactNumber::parseLexeme("1e400") < ExactNumber::parseLexeme("1e401"));
    }

    // --- CRITICAL 4: decimal normalization + trailing zeros ---
    {
        CHECK(ExactNumber::parseLexeme("1.50") == ExactNumber::parseLexeme("1.5"));
        CHECK(ExactNumber::parseLexeme("1.50").compare(ExactNumber::parseLexeme("1.5")) == 0);
        CHECK(ExactNumber::parseLexeme("0.10") == ExactNumber::parseLexeme("0.1"));
        CHECK(ExactNumber::parseLexeme("0.1") < ExactNumber::parseLexeme("0.2"));
        CHECK(ExactNumber::parseLexeme("0.2") < ExactNumber::parseLexeme("0.30"));
        CHECK(ExactNumber::parseLexeme("0.333") < ExactNumber::parseLexeme("0.334"));
        CHECK(ExactNumber::parseLexeme("123.4560") == ExactNumber::parseLexeme("123.456"));
        CHECK(ExactNumber::parseLexeme("0.00001000") == ExactNumber::parseLexeme("0.00001"));
    }

    // --- CRITICAL 5: exact multipleOf — 0.3 / 0.1 -> divisible, exact divmod ---
    {
        ExactNumber a = ExactNumber::parseLexeme("0.3");
        ExactNumber divisor = ExactNumber::parseLexeme("0.1");
        CHECK(isMultiple(a, divisor));              // 0.3 IS a multiple of 0.1 (exactly 3)
        CHECK(isMultiple(ExactNumber::parseLexeme("0.9"), divisor));
        CHECK(isMultiple(ExactNumber::parseLexeme("1.0"), divisor));
        CHECK(isMultiple(ExactNumber::parseLexeme("0.5"), ExactNumber::parseLexeme("0.1")));
        CHECK(isMultiple(ExactNumber::parseLexeme("0.3"), ExactNumber::parseLexeme("0.3")));
        CHECK(isMultiple(ExactNumber::parseLexeme("10"), ExactNumber::parseLexeme("0.01")));
        CHECK(isMultiple(ExactNumber::parseLexeme("0.0"), divisor)); // 0 is multiple of any
        // Not divisible (remainder exact non-zero).
        CHECK(!isMultiple(ExactNumber::parseLexeme("0.1"), ExactNumber::parseLexeme("0.3")));
        CHECK(!isMultiple(ExactNumber::parseLexeme("1.01"), ExactNumber::parseLexeme("0.03")));
        CHECK(!isMultiple(ExactNumber::parseLexeme("0.7"), ExactNumber::parseLexeme("0.3")));
        // divmod invariant: *this == q*divisor + remainder (exact), and q integer.
        {
            ExactNumber value = ExactNumber::parseLexeme("0.7");
            ExactNumber dv = ExactNumber::parseLexeme("0.3");
            ExactNumber q, r;
            value.divmod(dv, q, r);
            CHECK(q.mantissa() == 2);                 // 0.7 / 0.3 -> quotient 2
            CHECK(r == ExactNumber::parseLexeme("0.1")); // remainder 0.1
            CHECK(q.mul(dv).add(r) == value);         // exact back-substitution
        }
    }

    // --- CRITICAL 6: reject non-positive multipleOf ---
    {
        // Zero multipleOf MUST be refused (divmod throws -> never a silent modulo).
        CHECK(divmodZeroThrows(ExactNumber::parseLexeme("1.0")));
        // Caller-level positivity gate: 0 is not a positive multipleOf, negatives not either.
        CHECK(!isPositiveMultipleOf(ExactNumber::parseLexeme("0")));
        CHECK(!isPositiveMultipleOf(ExactNumber::parseLexeme("-0.1")));
        CHECK(!isPositiveMultipleOf(ExactNumber::parseLexeme("-3")));
        CHECK(isPositiveMultipleOf(ExactNumber::parseLexeme("0.1")));
        // A negative divisor is exact if erroneously presented, but the positivity
        // gate rejects it as a multipleOf before it is used as one.
        CHECK(isPositiveMultipleOf(ExactNumber::parseLexeme("3")));
    }

    // --- CRITICAL 7: integer-classification by VALUE not spelling ---
    {
        CHECK(ExactNumber::parseLexeme("1.0").isInteger());    // equals 1
        CHECK(ExactNumber::parseLexeme("1").isInteger());
        CHECK(ExactNumber::parseLexeme("1e0").isInteger());
        CHECK(ExactNumber::parseLexeme("100e-2").isInteger()); // equals 1
        CHECK(ExactNumber::parseLexeme("1.5e1").isInteger());  // equals 15
        CHECK(ExactNumber::parseLexeme("1e5").isInteger());    // 100000
        CHECK(ExactNumber::parseLexeme("-0.0").isInteger());   // 0
        CHECK(ExactNumber::parseLexeme("123456789012345678901234567890e10").isInteger());
        CHECK(!ExactNumber::parseLexeme("1.5").isInteger());
        CHECK(!ExactNumber::parseLexeme("1e-1").isInteger());  // 0.1
        CHECK(!ExactNumber::parseLexeme("0.3333333333333333333").isInteger());
        // 1e400 is a whole huge number -> integer.
        CHECK(ExactNumber::parseLexeme("1e400").isInteger());
    }

    // --- CRITICAL 8: const/enum equality across spellings (== is exact) ---
    {
        // enum [0.1, 1] vs instance spelling "1e-1" / "1.0" must match exactly.
        CHECK(ExactNumber::parseLexeme("1e-1") == ExactNumber::parseLexeme("0.1"));
        CHECK(ExactNumber::parseLexeme("1.0") == ExactNumber::parseLexeme("1"));
        CHECK(ExactNumber::parseLexeme("10e-1") == ExactNumber::parseLexeme("1.0"));
        CHECK(ExactNumber::parseLexeme("0.10") == ExactNumber::parseLexeme("0.1"));
        CHECK(ExactNumber::parseLexeme("-0") == ExactNumber::parseLexeme("0"));
    }

    // --- CRITICAL 9: overflow / size-limits -> explicit REFUSAL ---
    {
        CHECK(parseThrows("1e2147483648"));       // exponent just above int32 max
        CHECK(!parseThrows("1e2147483647"));      // int32 max exponent is fine
        CHECK(parseThrows("1e-2147483649"));      // below int32 min
        CHECK(parseThrows("1e99999999999999999999999999")); // pathological exp digits
        CHECK(parseThrows(""));                   // empty (no integer digit)
        CHECK(parseThrows("1.2e"));               // missing exponent digit
        CHECK(parseThrows("1e"));                 // missing exponent digit
        CHECK(parseThrows("1.2.3"));              // trailing garbage -> refusal
        CHECK(parseThrows("1abc"));               // trailing garbage
        // A very large mantissa is still representable (cpp_int, no overflow).
        std::string bigMantissa(10000, '9');
        ExactNumber bn = ExactNumber::parseLexeme(bigMantissa);
        CHECK(!bn.mantissa().is_zero());
        (void)bn;
    }

    // --- add / mul round-trips (supporting arithmetic) ---
    {
        ExactNumber x = ExactNumber::parseLexeme("0.1");
        ExactNumber y = ExactNumber::parseLexeme("0.2");
        CHECK(x.add(y) == ExactNumber::parseLexeme("0.3"));     // 0.1+0.2 == 0.3 exactly
        CHECK(ExactNumber::parseLexeme("1.5").mul(ExactNumber::parseLexeme("2"))
                  == ExactNumber::parseLexeme("3.0"));
        CHECK(ExactNumber::parseLexeme("0.3").mul(ExactNumber::parseLexeme("3"))
                  == ExactNumber::parseLexeme("0.9"));
        CHECK(ExactNumber::parseLexeme("1e10").mul(ExactNumber::parseLexeme("1e10"))
                  == ExactNumber::parseLexeme("1e20"));
    }

    std::printf("oas31_exact_number_test: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
