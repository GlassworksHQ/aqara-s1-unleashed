#include <string.h>
#include "driver/uart.h"
#include "driver/usb_serial_jtag.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#define PANEL_UART UART_NUM_1
#define PANEL_TX   40
#define PANEL_RX   41
#define BAUD       115200
#define BUFSZ      1024
void app_main(void){
    usb_serial_jtag_driver_config_t uj = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
    usb_serial_jtag_driver_install(&uj);
    uart_config_t c = { .baud_rate=BAUD, .data_bits=UART_DATA_8_BITS, .parity=UART_PARITY_DISABLE,
        .stop_bits=UART_STOP_BITS_1, .flow_ctrl=UART_HW_FLOWCTRL_DISABLE, .source_clk=UART_SCLK_DEFAULT };
    uart_driver_install(PANEL_UART, BUFSZ*2, 0, 0, NULL, 0);
    uart_param_config(PANEL_UART, &c);
    uart_set_pin(PANEL_UART, PANEL_TX, PANEL_RX, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
    static uint8_t buf[BUFSZ];
    for(;;){
        int m = uart_read_bytes(PANEL_UART, buf, BUFSZ, 1);
        if (m>0) usb_serial_jtag_write_bytes(buf, m, 10/portTICK_PERIOD_MS);
        int h = usb_serial_jtag_read_bytes(buf, BUFSZ, 1);
        if (h>0) uart_write_bytes(PANEL_UART, buf, h);
    }
}
