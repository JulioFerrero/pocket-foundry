# Debian 13, like the libraries we ship from it: everything links against glibc <= 2.41 and runs on our 2.44.
FROM --platform=linux/arm64 debian:trixie

# glibc, and the tools every piece uses
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential gawk bison python3 texinfo gettext jq curl ca-certificates \
        xz-utils patch file linux-libc-dev \
    && rm -rf /var/lib/apt/lists/*

# Mesa
RUN apt-get update && apt-get install -y --no-install-recommends \
        meson ninja-build pkg-config python3-mako python3-yaml python3-packaging \
        flex glslang-tools libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev \
        libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxrandr-dev libxxf86vm-dev \
        libxshmfence-dev libxcb1-dev libxcb-glx0-dev libxcb-dri2-0-dev libxcb-dri3-dev \
        libxcb-present-dev libxcb-sync-dev libxcb-shm0-dev libxcb-randr0-dev \
        libxcb-xfixes0-dev libglvnd-dev \
    && rm -rf /var/lib/apt/lists/*

# SDL
RUN apt-get update && apt-get install -y --no-install-recommends \
        cmake libxcursor-dev libxi-dev libxss-dev \
    && rm -rf /var/lib/apt/lists/*

# libhybris, for the phone's own Vulkan driver
RUN apt-get update && apt-get install -y --no-install-recommends \
        autoconf automake libtool libwayland-dev libwayland-egl-backend-dev \
    && rm -rf /var/lib/apt/lists/*

# The libraries assemble copies into the runtime, and the X keyboard data
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglx0 libegl1 libglvnd0 libvulkan1 \
        libx11-6 libx11-xcb1 libxext6 libxrandr2 libxi6 libxcursor1 libxfixes3 libxss1 \
        libxrender1 libxkbcommon0 libxkbcommon-x11-0 libxxf86vm1 xkb-data \
    && rm -rf /var/lib/apt/lists/*
