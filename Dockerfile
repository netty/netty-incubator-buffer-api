# Prepare environment
FROM fedora:33
RUN dnf -y install file findutils unzip zip libXtst-devel libXt-devel libXrender-devel libXrandr-devel \
           libXi-devel cups-devel fontconfig-devel alsa-lib-devel make autoconf diffutils git clang \
           java-latest-openjdk-devel automake libtool

# Build panama-foreign openjdk
WORKDIR /home/build
RUN git clone --depth 1 --branch foreign-memaccess+abi https://github.com/openjdk/panama-foreign.git panama-foreign
WORKDIR /home/build/panama-foreign
RUN chmod +x configure
RUN ./configure --with-debug-level=fastdebug \
                --with-toolchain-type=clang \
                --with-vendor-name=jackalope \
                --enable-warnings-as-errors=no
RUN make images && mv build/linux-x86_64-server-fastdebug/images/jdk /home/jdk && rm -fr *
ENV JAVA_HOME="/home/jdk"

# Prepare our own build environment
WORKDIR /home/build
RUN curl https://downloads.apache.org/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz | tar -xz
ENV PATH=/home/build/apache-maven-3.6.3/bin:$PATH

# Prepare a snapshot of Netty 5
RUN git clone --depth 1 -b main https://github.com/netty/netty.git netty \
    && cd netty \
    && mvn install -DskipTests -T1C -B -am \
    && cd .. \
    && rm -fr netty

# Prepare our own build
RUN mkdir buffer-api && mkdir buffer-memseg && mkdir buffer-tests
COPY pom.xml pom.xml
COPY buffer-memseg/pom.xml buffer-memseg/pom.xml
COPY buffer-tests/pom.xml buffer-tests/pom.xml
RUN mvn --version
RUN mvn install dependency:go-offline surefire:test checkstyle:check -ntp

# Copy over the project code and run our build
COPY . .
# Make sure Maven has enough memory to keep track of all the tests we run
ENV MAVEN_OPTS="-Xmx4g -XX:+HeapDumpOnOutOfMemoryError"
# Run tests
CMD mvn verify -o -B -C -T1C -fae -nsu
