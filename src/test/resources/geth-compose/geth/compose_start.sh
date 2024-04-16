#!/bin/sh
geth --nousb \
    --http --http.addr=0.0.0.0 --http.vhosts=* \
    --dev --dev.period=1 --rpc.allow-unprotected-txs
