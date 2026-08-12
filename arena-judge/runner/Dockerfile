# The image untrusted submissions execute in.
#
#   docker build -t codearena/arena-runner:dev arena-judge/runner
#
# Deliberately minimal. Every tool present is one a submission can invoke, so the image carries
# a compiler and an interpreter and nothing else - no curl, no wget, no package manager, no
# shell utilities beyond what Alpine's base needs to function. There is no network to use them
# on either, but defence that relies on a single control is not defence.
FROM alpine:3.20

# python3 for interpreted submissions, g++ for compiled ones. libstdc++ is needed at runtime by
# anything g++ produces.
RUN apk add --no-cache python3 g++ libstdc++

# A non-root user with no shell and no home directory to write into. Containers are also started
# with --user, so this is belt and braces: an image that defaults to root is one --user flag away
# from running submissions as root.
RUN adduser -D -H -s /sbin/nologin -u 10001 runner

# The only writable location, and it is mounted as a size-capped tmpfs at run time, so a
# submission that writes until the disk fills exhausts a few megabytes of RAM instead.
WORKDIR /work

USER runner

# No ENTRYPOINT: the judge supplies the exact argv it wants and never goes through a shell, so
# there is nothing here for a crafted filename or argument to break out of.
CMD ["/bin/false"]
