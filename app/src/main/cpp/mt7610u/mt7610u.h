#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*mt7610u_frame_cb)(const uint8_t *frame, int len, int rssi,
                                 int channel, void *user);

/* Receive-only MT7610U bring-up. Never submits 802.11 TX. */
int mt7610u_open(int fd, const char *fw_path, char *err, size_t errlen);
int mt7610u_start_rx(int channel, mt7610u_frame_cb cb, void *user,
                     char *err, size_t errlen);
void mt7610u_start_pump(void);
void mt7610u_set_thread_exit_hook(void (*fn)(void));
int mt7610u_set_channel(int channel);
int mt7610u_push_rx(const uint8_t *buf, int n);
void mt7610u_rx_poll(int last_rc);
void mt7610u_kick_rx(void);
void mt7610u_rx_stats(unsigned *urb, unsigned *bytes, unsigned *hits);
void mt7610u_rx_diag(char *buf, size_t len);
int mt7610u_rx_endpoint(void);
int mt7610u_is_open(void);
void mt7610u_stop(void);

#ifdef __cplusplus
}
#endif
