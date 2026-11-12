# Debian 13, like the libraries we ship from it: everything links against glibc <= 2.41 and runs on our 2.44.
FROM --platform=linux/arm64 debian:trixie
COPY deps.sh /
RUN sh /deps.sh && rm /deps.sh
