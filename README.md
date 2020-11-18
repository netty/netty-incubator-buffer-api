# Netty Incubator Buffer API

This repository is incubating a new buffer API proposed for Netty 5.

## Building and Testing

Short version: just run `make`.

The project currently relies on snapshot versions of the [Panama Foreign](https://github.com/openjdk/panama-foreign) fork of OpenJDK.
This allows us to test out the must recent version of the `jdk.incubator.foreign` APIs, but also make building and local development more involved.
To simplify things, we have a Docker based build, controlled via a Makefile with the following commands:

* `image` – build the docker image. This includes building a snapshot of OpenJDK, and download all relevant Maven dependencies.
* `test` – run all tests in a docker container. This implies `image`. The container is automatically deleted afterwards.
* `dbg` – drop into a shell in the build container, without running the build itself. The debugging container is not deleted afterwards.
* `clean` – remote the debugging container created by `dbg`.
* `build` – build binaries and run all tests in a container, and copy the `target` directory out of the container afterwards. This is the default build target.
