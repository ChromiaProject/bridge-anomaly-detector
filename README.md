# Bridge anomaly detector

Application to monitor bridges EVM `withdraw requests` and verify them again a postchain instance. If an anomaly is
found it will pause the bridge contract.

# Build

`mvn clean install --activate-profiles docker`

Running it requires 1-2 config files:
 1. Either a bridge anomaly detector config (see `doc/example.config`) with the nodes private key set.
 2. Or a bridge anomaly detector config file + a ordinary node.config file (which the private key will be read from)

# Run container

```
docker run --rm -it \
    --volume $(pwd)/doc/example.properties:/opt/chromaway/bad/config.properties \
    --volume $(pwd)/../directory1-example/config/config.1.properties:/opt/chromaway/directory1-example/config/config.0.properties bridge-anomaly-detector \
    config.properties
```

# Run native application

```
tar -zxvf bridge-anomaly-detector-<version>-dist.tar.gz
./bridge-anomaly-detector/bin/bad <config file>
```

# TODO

- start only if node has built a block - if possible.
- add randomness +- 1-5 minutes to reduce risk of sending pause tx at the same time?
- Pull full EC for testing (not using the current mocked version)?
- Integration test in postchain-chromia?

## Copyright & License information

Copyright (c) 2017–2024 ChromaWay AB. All rights reserved.

This software can be used either under the terms of commercial license
obtained from ChromaWay AB, or, alternatively, under the terms
of the GNU General Public License with additional linking exceptions.
See file LICENSE for details.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
