#!/bin/bash
rm soc-gemmini-multisim/*.ext2
python gen-benchmark-rootfs.py -w soc-gemmini-multisim.json -b ~/firesim/sw/firesim-software/images/br-base.img -s $softwaregemmini/build/overlay
