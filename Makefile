.PHONY: image test dbg clean build
.DEFAULT_GOAL := build

image:
	docker build --tag netty-incubator-buffer:build .

test: image
	docker run --rm --name build-container netty-incubator-buffer:build

dbg:
	docker create --name build-container-dbg --entrypoint /bin/bash -t netty-incubator-buffer:build
	docker start build-container-dbg
	docker exec -it build-container-dbg bash

clean:
	docker rm -fv build-container-dbg

build: image
	docker create --name build-container netty-incubator-buffer:build
	docker start -a build-container
	docker wait build-container
	docker cp build-container:/home/build/target .
	docker rm build-container
