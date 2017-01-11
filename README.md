# source-compose
a build and deploy tool based on docker and docker-compose

## How to use

 1. Compile & package: cd to project folder and run

    sbt clean compile assembly

 2. Get the executable jar

    cp  target/scala-${scala-version}/source-compose-assembly-1.0.jar .

 3. Envirement setting:

    COMPOSE_WORKSPACE=${folder_where_your_projects_located}

 4. Created your project files with docker-compose format.
 5. java -jar source-compose-assembly-1.0.jar
 6. alternative, you can make the executable file more elegant under shell like command lines

    cat scompose.sh source-compose-assembly-1.0.jar > scompose
then sh scompose
 






