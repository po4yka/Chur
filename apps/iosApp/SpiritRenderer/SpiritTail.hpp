#pragma once
#include <array>
#include "SpiritMotion.hpp"

namespace chur {
// Generated from the bundled asset manifest, never substitute a common local axis.
inline constexpr std::array<const char*, 8> tailNames = {"Tail_01", "Tail_02", "Tail_03", "Tail_04", "Tail_05", "Tail_06", "Tail_07", "Tail_08"};
inline constexpr std::array<std::array<float, 3>, 8> tailAxes = {{
    {-6.6257396251e-01f, -7.4066966418e-08f, 7.4899649145e-01f},
    {-6.1394064524e-01f, -9.0840657045e-08f, 7.8935219270e-01f},
    {-3.8964007714e-01f, -1.3761976636e-07f, 9.2096721456e-01f},
    {3.9968044846e-02f, -2.1324455056e-07f, 9.9920095846e-01f},
    {4.8564314381e-01f, -2.6638179395e-07f, 8.7415715799e-01f},
    {7.4740950734e-01f, -2.7011082288e-07f, 6.6436362659e-01f},
    {8.3205038747e-01f, -2.9271955926e-07f, 5.5470005652e-01f},
    {9.4055390393e-01f, -2.5674388382e-07f, 3.3964445204e-01f},
}};
inline constexpr std::array<float, 8> tailLimits = {0.0000000000e+00f, 0.0000000000e+00f, 4.0000000000e-03f, 8.0000000000e-03f, 1.2000000000e-02f, 1.6000000000e-02f, 2.0000000000e-02f, 2.4000000000e-02f};
inline void advanceTail(std::array<float, 8>& signals, float target, float delta, bool reduced) {
    if (reduced) { signals.fill(0.f); return; }
    const float dt = std::isfinite(delta) ? std::clamp(delta, 0.f, .25f) : 0.f;
    const int steps = std::max(1, int(std::ceil(dt * 240.f)));
    const float step = dt / steps;
    for (int n = 0; n < steps; ++n) {
        float upstream = boundedInput(target);
        for (size_t i = 0; i < signals.size(); ++i) {
            const float old = signals[i];
            const float blend = 1.f - std::exp(-step * (12.f - float(i)));
            signals[i] = boundedInput(signals[i] + (upstream - signals[i]) * blend);
            upstream = old;
        }
    }
}
} // namespace chur
