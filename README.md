# TODO
- start only if node has built a block - if possible.
- add randomness +- 1-5 minutes to reduce risk of sending pause tx at the same time?
- pause call
- fix logging
- build docker image
- ci pipelines
- clean up pom.xml
- Pull full EC for testing (not using the current mocked version)?
- This readme - short description and usage
- Integration test in postchain-chromia?


# Bridge anomaly detector

TODO

# Build and use

`mvn clean install -DskipTests -Dlocal --activate-profiles docker`

`docker run --rm -it --volume $(pwd)/doc/example.properties:/opt/chromaway/bad/config.properties --volume $(pwd)/../directory1-example/config/config.1.properties:/opt/chromaway/directory1-example/config/config.0.properties bridge-anomaly-detector config.properties`


## Copyright & License information

Copyright (c) 2017–2024 ChromaWay AB. All rights reserved.

This software can be used either under the terms of commercial license
obtained from ChromaWay AB, or, alternatively, under the terms
of the GNU General Public License with additional linking exceptions.
See file LICENSE for details.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
