# Runs the real Minecraft client headless: Xvfb for the display, Mesa llvmpipe for OpenGL.
# Nothing here touches the host's screen, cursor or keyboard.
FROM eclipse-temurin:25-jdk
# ports.ubuntu.com is slow from here; pass --build-arg APT_MIRROR=http://mirrors.ustc.edu.cn/ubuntu-ports to swap it
ARG APT_MIRROR=
RUN if [ -n "$APT_MIRROR" ]; then sed -i "s|http://ports.ubuntu.com/ubuntu-ports|$APT_MIRROR|g" /etc/apt/sources.list.d/ubuntu.sources; fi \
 && apt-get -o Acquire::Retries=8 update \
 && DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=8 install -y --no-install-recommends \
      xvfb xauth libgl1 libgl1-mesa-dri libegl1 libglx-mesa0 mesa-utils \
      libx11-6 libxext6 libxrandr2 libxinerama1 libxcursor1 libxi6 libxxf86vm1 libxtst6 \
      libasound2t64 procps \
 && rm -rf /var/lib/apt/lists/*
ENV LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
