#!/bin/bash

firesim infrasetup -c $runtime -a $hwdb -r $recipes && firesim runworkload -c $runtime -a $hwdb -r $recipes
