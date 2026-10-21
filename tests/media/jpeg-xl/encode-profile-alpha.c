/* Generates synthetic ICC and associated-alpha samples with reference libjxl. */
#include <jxl/encode.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    if (argc != 2 && argc != 3) return 1;
    JxlEncoder *encoder = JxlEncoderCreate(NULL);
    if (!encoder) return 1;
    const int associated = argc == 2;
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = 16;
    info.ysize = 8;
    info.bits_per_sample = 8;
    info.num_color_channels = 3;
    info.uses_original_profile = JXL_TRUE;
    info.num_extra_channels = associated ? 1 : 0;
    info.alpha_bits = associated ? 8 : 0;
    info.alpha_premultiplied = associated ? JXL_TRUE : JXL_FALSE;
    if (JxlEncoderUseContainer(encoder, JXL_TRUE) != JXL_ENC_SUCCESS ||
        JxlEncoderSetBasicInfo(encoder, &info) != JXL_ENC_SUCCESS) return 1;
    if (associated) {
        JxlColorEncoding color;
        JxlColorEncodingSetToSRGB(&color, JXL_FALSE);
        if (JxlEncoderSetColorEncoding(encoder, &color) != JXL_ENC_SUCCESS) return 1;
    } else {
        FILE *profile = fopen(argv[2], "rb");
        if (!profile || fseek(profile, 0, SEEK_END) != 0) return 1;
        const long length = ftell(profile);
        if (length <= 0 || length > 3 * 1024 * 1024 || fseek(profile, 0, SEEK_SET) != 0) return 1;
        uint8_t *icc = malloc((size_t)length);
        if (!icc || fread(icc, 1, (size_t)length, profile) != (size_t)length || fclose(profile) != 0) return 1;
        const JxlEncoderStatus profile_status = JxlEncoderSetICCProfile(encoder, icc, (size_t)length);
        free(icc);
        if (profile_status != JXL_ENC_SUCCESS) return 1;
    }
    JxlEncoderFrameSettings *settings = JxlEncoderFrameSettingsCreate(encoder, NULL);
    if (!settings || JxlEncoderSetFrameLossless(settings, JXL_TRUE) != JXL_ENC_SUCCESS) return 1;
    const unsigned channels = associated ? 4 : 3;
    JxlPixelFormat format = {channels, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    uint8_t pixels[16 * 8 * 4];
    for (unsigned pixel = 0; pixel < 16 * 8; pixel++) {
        pixels[pixel * channels] = associated ? 128 : 255;
        pixels[pixel * channels + 1] = 0;
        pixels[pixel * channels + 2] = 0;
        if (associated) pixels[pixel * channels + 3] = 128;
    }
    if (JxlEncoderAddImageFrame(settings, &format, pixels, 16 * 8 * channels) != JXL_ENC_SUCCESS) return 1;
    JxlEncoderCloseInput(encoder);
    const size_t capacity = 4 * 1024 * 1024;
    uint8_t *output = malloc(capacity), *next = output;
    if (!output) return 1;
    size_t available = capacity;
    if (JxlEncoderProcessOutput(encoder, &next, &available) != JXL_ENC_SUCCESS) return 1;
    FILE *file = fopen(argv[1], "wb");
    if (!file) return 1;
    const size_t size = (size_t)(next - output);
    const int result = fwrite(output, 1, size, file) == size && fclose(file) == 0 ? 0 : 1;
    JxlEncoderDestroy(encoder);
    free(output);
    return result;
}
