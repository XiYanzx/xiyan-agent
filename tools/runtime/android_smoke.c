#include <stdio.h>
#include <unistd.h>

int main(void) {
    printf("Xiyan Android toolchain smoke; page_size=%ld\n", sysconf(_SC_PAGESIZE));
    return 0;
}
