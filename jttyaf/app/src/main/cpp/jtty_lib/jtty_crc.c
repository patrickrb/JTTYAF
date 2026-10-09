// jtty_crc.c — CRC-12 over the 34-bit payload (docs/jtty-protocol.md §2.2).
//
// Polynomial 0x80F with the implicit x^12 term (x^12+x^11+x^3+x^2+x+1),
// register initialised to 0, no reflection, no final XOR, bits taken MSB
// first, computed over exactly the 34 payload bits (no zero padding to a byte
// or word boundary — the padding variants the spec left open were ruled out
// against the WSJT-X sample recording, see SOURCES.md).

#include "jtty_internal.h"

#define CRC12_POLY 0x80Fu

// Bit-serial augmented CRC of the `nbits` low bits of `bits`, MSB first:
// M(x)*x^12 mod G(x), the usual "CRC" (equivalent to appending 12 zero bits to
// the message and taking the plain remainder).
static uint16_t crc12_serial(uint64_t bits, int nbits)
{
    uint32_t reg = 0;
    for (int i = nbits - 1; i >= 0; --i) {
        uint32_t bit = (uint32_t)((bits >> i) & 1u);
        uint32_t fb = ((reg >> 11) & 1u) ^ bit;
        reg = (reg << 1) & 0xFFFu;
        if (fb) reg ^= CRC12_POLY;
    }
    return (uint16_t)(reg & 0xFFFu);
}

uint16_t jtty_crc12(uint64_t payload34)
{
    payload34 &= (1ull << JTTY_PAYLOAD_BITS) - 1u;
    return crc12_serial(payload34, JTTY_PAYLOAD_BITS);
}

uint64_t jtty_add_crc(uint64_t payload34)
{
    payload34 &= (1ull << JTTY_PAYLOAD_BITS) - 1u;
    return (payload34 << JTTY_CRC_BITS) | jtty_crc12(payload34);
}
