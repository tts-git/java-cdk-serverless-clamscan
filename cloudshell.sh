# This file should be sourced before running install on AWS Cloud Shell
# source cloudshell.sh

set -e

echo "Installing Java 25 (Amazon Corretto)..."
sudo yum install -y java-25-amazon-corretto-devel

echo "Verifying Java Version..."
java -version

echo "Installing latest Maven locally..."
MAVEN_VERSION=3.9.10

# Download and untar in your HOME directory
curl -fsSL https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz -o maven.tar.gz
mkdir -p $HOME/maven
tar -xzvf maven.tar.gz -C $HOME/maven --strip-components=1
rm maven.tar.gz

# Set environment variables
export M2_HOME=$HOME/maven
export MAVEN_HOME=$HOME/maven
export PATH=$M2_HOME/bin:$PATH

echo "Verifying Maven Version..."
mvn -version

echo "Installing AWS CDK globally..."
sudo npm install -g aws-cdk

echo "Verifying CDK Version..."
cdk version

echo "✅ Environment ready. You can now run 'mvn install'"
