// Host only, touches no device. Prints the TFLite Micro audio frontend's features for the synthetic
// signal MicroFrontendTest uses (tools/voice/mww_reference.py synthetic()), one JSON row of 40 uint16
// values per 10 ms, with microWakeWord's settings. Build it against the C in pymicro-features v2.0.2
// with and without FMA contraction to see the one weight clang fuses (docs/research/microwakeword.md):
//
//   git clone --depth 1 --branch v2.0.2 https://github.com/rhasspy/pymicro-features /tmp/pmf
//   L=/tmp/pmf/tensorflow/lite/experimental/microfrontend/lib
//   clang++ -O2 -ffp-contract=off -std=c++17 -I/tmp/pmf -I/tmp/pmf/kissfft tools/voice/microfrontend_check.cc \
//     $L/{fft,fft_util,filterbank,filterbank_util,frontend,frontend_util,kiss_fft_int16,log_lut,log_scale,log_scale_util,noise_reduction,noise_reduction_util,pcan_gain_control,pcan_gain_control_util,window,window_util}.cc \
//     -o /tmp/mfcheck && /tmp/mfcheck > /tmp/synthetic-nofma.json
#include <stdint.h>
#include <stdio.h>
extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}

static int16_t buf[32000];
static int n = 0;

static void lcg(int count, long long seed, int span) {
  long long x = seed;
  for (int i = 0; i < count; i++) {
    x = (x * 1103515245LL + 12345LL) % 2147483648LL;
    buf[n++] = (int16_t)((x >> 8) % (2LL * span) - span);
  }
}

int main() {
  lcg(4000, 1, 32767);
  lcg(3200, 4, 2000);
  lcg(3200, 2, 40);
  for (int i = 0; i < 2400; i++) buf[n++] = 0;
  lcg(4000, 3, 3000);

  FrontendConfig cfg;
  FrontendState st;
  cfg.window.size_ms = 30;
  cfg.window.step_size_ms = 10;
  cfg.filterbank.num_channels = 40;
  cfg.filterbank.lower_band_limit = 125.0f;
  cfg.filterbank.upper_band_limit = 7500.0f;
  cfg.noise_reduction.smoothing_bits = 10;
  cfg.noise_reduction.even_smoothing = 0.025f;
  cfg.noise_reduction.odd_smoothing = 0.06f;
  cfg.noise_reduction.min_signal_remaining = 0.05f;
  cfg.pcan_gain_control.enable_pcan = 1;
  cfg.pcan_gain_control.strength = 0.95f;
  cfg.pcan_gain_control.offset = 80.0f;
  cfg.pcan_gain_control.gain_bits = 21;
  cfg.log_scale.enable_log = 1;
  cfg.log_scale.scale_shift = 6;
  if (!FrontendPopulateState(&cfg, &st, 16000)) return 1;

  printf("[");
  int rows = 0;
  for (int i = 0; i + 160 <= n; i += 160) {
    size_t read;
    FrontendOutput o = FrontendProcessSamples(&st, buf + i, 160, &read);
    if (!o.size) continue;
    printf("%s[", rows++ ? "," : "");
    for (size_t k = 0; k < o.size; k++) printf("%s%u", k ? "," : "", o.values[k]);
    printf("]");
  }
  printf("]\n");
  return 0;
}
