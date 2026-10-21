#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace chur {
// All methods, including destruction, run on the UIKit main thread.
// Owns its Engine. The caller owns and retains the CAMetalLayer for this lifetime.
class SpiritScene final {
public:
    SpiritScene(void* metalLayer, const uint8_t* glb, size_t byteCount);
    ~SpiritScene();
    SpiritScene(const SpiritScene&) = delete;
    SpiritScene& operator=(const SpiritScene&) = delete;
    bool ready() const;
    const std::string& error() const;
    void resize(uint32_t width, uint32_t height);
    void update(const std::string& clip, int32_t serial, int32_t motionSerial, bool reducedMotion, float x, float y);
    bool render(double deltaSeconds);
    void pauseMotion();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
} // namespace chur
