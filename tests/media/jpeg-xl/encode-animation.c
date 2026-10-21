/* Generates a synthetic finite animation with the libjxl reference encoder. */
#include <jxl/encode.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc != 2) return 1;
    JxlEncoder *encoder = JxlEncoderCreate(NULL);
    if (!encoder) return 1;
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = 48;
    info.ysize = 24;
    info.bits_per_sample = 8;
    info.num_color_channels = 3;
    info.uses_original_profile = JXL_TRUE;
    info.have_animation = JXL_TRUE;
    info.animation.tps_numerator = 1000;
    info.animation.tps_denominator = 1;
    info.animation.num_loops = 2;
    if (JxlEncoderUseContainer(encoder, JXL_TRUE) != JXL_ENC_SUCCESS ||
        JxlEncoderSetBasicInfo(encoder, &info) != JXL_ENC_SUCCESS) return 1;
    JxlColorEncoding color;
    JxlColorEncodingSetToSRGB(&color, JXL_FALSE);
    if (JxlEncoderSetColorEncoding(encoder, &color) != JXL_ENC_SUCCESS) return 1;
    JxlEncoderFrameSettings *settings = JxlEncoderFrameSettingsCreate(encoder, NULL);
    if (!settings || JxlEncoderSetFrameLossless(settings, JXL_TRUE) != JXL_ENC_SUCCESS) return 1;
    JxlPixelFormat format = {3, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    uint8_t pixels[48 * 24 * 3];
    const uint32_t durations[] = {80, 120, 160};
    for (unsigned frame = 0; frame < 3; frame++) {
        memset(pixels, 0, sizeof(pixels));
        for (unsigned pixel = 0; pixel < 48 * 24; pixel++) pixels[pixel * 3 + frame] = 255;
        JxlFrameHeader header;
        JxlEncoderInitFrameHeader(&header);
        header.duration = durations[frame];
        if (JxlEncoderSetFrameHeader(settings, &header) != JXL_ENC_SUCCESS ||
            JxlEncoderAddImageFrame(settings, &format, pixels, sizeof(pixels)) != JXL_ENC_SUCCESS) return 1;
    }
    JxlEncoderCloseInput(encoder);
    uint8_t output[4096];
    uint8_t *next = output;
    size_t available = sizeof(output);
    if (JxlEncoderProcessOutput(encoder, &next, &available) != JXL_ENC_SUCCESS) return 1;
    FILE *file = fopen(argv[1], "wb");
    if (!file) return 1;
    const size_t size = (size_t)(next - output);
    const int result = fwrite(output, 1, size, file) == size && fclose(file) == 0 ? 0 : 1;
    JxlEncoderDestroy(encoder);
    return result;
}
