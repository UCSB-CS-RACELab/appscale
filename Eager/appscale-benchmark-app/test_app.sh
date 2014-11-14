#!/bin/bash
c=1
echo "Load testing: $1"
echo "Iterations: $2"
rm results.txt
while [ $c -le $2 ]
do
  echo "Iteration $c"
  #curl -w "@curl-format.txt" -o /dev/null -s $1 | grep "time_total" >> results.txt
  #curl -w "@curl-format.txt" -o /dev/null -X POST -d "comment=Foo" -s $1 | grep "time_total" >> results.txt
  curl -w "@curl-format.txt" -o /dev/null -X POST -d "firstName=Foo&lastName=Bar" -s $1 | grep "time_total" >> results.txt
  sleep 1
  (( c++ ))
done
