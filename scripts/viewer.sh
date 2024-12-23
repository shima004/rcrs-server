DIR=$(dirname $0)

cd $DIR/..

# print the map folder
for i in $(ls $DIR/rescue-log) 
do
  echo $i
done

#input map name
echo "Please input the map name:"
read mapname

./gradlew logViewer --args="'-c' 'config/logviewer.cfg' 'rescue-log/$mapname'"
