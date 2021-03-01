# Prepare environment
FROM fedora:33
RUN dnf -y install file findutils unzip zip libXtst-devel libXt-devel libXrender-devel libXrandr-devel \
           libXi-devel cups-devel fontconfig-devel alsa-lib-devel make autoconf diffutils git clang \
           java-latest-openjdk-devel

# Build panama-foreign openjdk
WORKDIR /home/build
RUN git clone https://github.com/openjdk/panama-foreign.git panama-foreign
WORKDIR /home/build/panama-foreign
RUN chmod +x configure
RUN ./configure --with-debug-level=fastdebug \
                --with-toolchain-type=clang \
                --with-vendor-name=jackalope \
                --enable-warnings-as-errors=no
RUN make images
ENV JAVA_HOME="/home/build/panama-foreign/build/linux-x86_64-server-fastdebug/images/jdk"

# Prepare our own build environment
WORKDIR /home/build
RUN curl https://downloads.apache.org/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz | tar -xz
ENV PATH=/home/build/apache-maven-3.6.3/bin:$PATH

# Prepare a snapshot of Netty 5
RUN git clone -b master https://github.com/netty/netty.git netty
WORKDIR /home/build/netty
RUN mvn install -DskipTests -T1C -B -am -pl buffer,handler
WORKDIR /home/build

# Prepare our own build
COPY pom.xml pom.xml
RUN mvn dependency:go-offline surefire:test -ntp

# Copy over the project code and run our build
COPY . .
CMD mvn verify -o -B -C -T1C -fae -nsu -npu
