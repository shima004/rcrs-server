#!/bin/bash

function kill_window() {
  if [ $count -eq 0 ]; then
    count=$((count+1))
    ./kill.sh
    echo window was killed
    #now time
    cp -r ../logs/log ../archive/$map_name-$(date +%Y-%m-%d-%H-%M-%S)
    echo "rescue log was saved to ../archive/$map_name-$(date +%Y-%m-%d-%H-%M-%S)"
  fi
}

# trap ctrl-c
trap kill_window SIGINT

DIR=$(dirname $0)
count=0

# print the map folder
for i in $(ls $DIR/../maps) 
do
  echo $i
done

# check if map name is provided as an argument
if [ -z "$1" ]; then
  echo "Please input the map name:"
  read map_name
else
  map_name=$1
fi

# option
NO_DUI=$2

./start-comprun.sh -m ../maps/$map_name/map -c ../maps/$map_name/config $NO_DUI

kill_window
