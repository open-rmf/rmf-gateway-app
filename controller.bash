#!/bin/bash
SPEED=0.60
while [ true ] ; do
echo "wasd to move; space to stop; eq to sped up / down; SPEED=$SPEED"
read -t 3 -n 1 k 
echo "KEY: $k"
[[ $k == 'a' ]] && mosquitto_pub -t cmd_vel -m "-$SPEED,0.0"
[[ $k == 'd' ]] && mosquitto_pub -t cmd_vel -m "$SPEED,0.0"
[[ $k == 'w' ]] && mosquitto_pub -t cmd_vel -m "0.0,-$SPEED"
[[ $k == 's' ]] && mosquitto_pub -t cmd_vel -m "0.0,$SPEED"
[[ $k == 'e' ]] && SPEED=$(bc <<< "$SPEED + 0.05")
[[ $k == 'q' ]] && SPEED=$(bc <<< "$SPEED - 0.05")
[[ $k == '' ]] && mosquitto_pub -t cmd_vel -m "0.0,0.0"
SPEED=$(bc  <<< "$SPEED % 1" | awk '{printf "%f", $0}') 
done


