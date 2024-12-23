#!/bin/bash

function kill_window() {
  ./kill.sh
  echo window was killed
  #now time
  cp -r ../logs/log ../archive/$mapname-$(date +%Y-%m-%d-%H-%M-%S)
  echo "rescue log was saved to ../archive/$mapname-$(date +%Y-%m-%d-%H-%M-%S)"
}

# trap ctrl-c
trap kill_window SIGINT

DIR=$(dirname $0)

# print the map folder
for i in $(ls $DIR/../maps) 
do
  echo $i
done

# check if map name is provided as an argument
if [ -z "$1" ]; then
  echo "Please input the map name:"
  read mapname
else
  mapname=$1
fi

# option
NO_DUI=$2

./start-comprun.sh -m ../maps/$mapname/map -c ../maps/$mapname/config $NO_DUI

kill_window
