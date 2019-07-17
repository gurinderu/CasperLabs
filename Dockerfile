FROM oracle/graalvm-ce:latest

RUN gu install native-image
RUN curl https://bintray.com/sbt/rpm/rpm | tee /etc/yum.repos.d/bintray-sbt-rpm.repo
RUN yum update -y && yum install -y sbt
RUN sbt version
COPY . /tmp/client
WORKDIR /tmp/client
RUN sbt 'show client/graalvm-native-image:packageBin'




