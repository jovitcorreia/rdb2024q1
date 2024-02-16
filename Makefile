BIN=./target/rdb2024q1-runner
JAR=./target/rdb2024q1-1.0.0-SNAPSHOT.jar
DOCKERFILE=./src/main/docker/Dockerfile.native
IMAGE=jovitcorreia/rdb2024q1

.PHONY: all git_pull build_native build_image

all: git_pull build_native build_image

git_pull:
	git pull

build_native:
	./mvnw package -Dnative -Dquarkus.native.container-build=true

build_image:
	docker build -f $(DOCKERFILE) -t $(IMAGE) .
