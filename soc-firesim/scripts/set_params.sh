#!/bin/bash

export runtime=$PWD/ini/config_runtime.ini
export hwdb=$PWD/ini/config_hwdb.ini
export recipes=$PWD/ini/config_build_recipes.ini
export build=$PWD/ini/config_build.ini

export workload=/home/centos/firesim/deploy/workloads
export gemmini=/home/centos/firesim/sim/target-rtl/chipyard/generators/gemmini
export softwaregemmini=/home/centos/firesim/sim/target-rtl/chipyard/generators/gemmini/software/gemmini-rocc-tests
export confchipyard=/home/centos/firesim/sim/target-rtl/chipyard/generators/chipyard/src/main/scala/config
export conffirechip=/home/centos/firesim/sim/target-rtl/chipyard/generators/firechip/src/main/scala
export chipyard=/home/centos/firesim/sim/target-rtl/chipyard
