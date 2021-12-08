#!/bin/bash
python gen-benchmark-rootfs.py -w soc-gemmini.json -b ~/firesim/sw/firesim-software/images/br-base.img -s $softwaregemmini/build/overlay
