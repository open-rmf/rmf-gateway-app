from tkinter import *
import os

root = Tk()
frame = Frame(root)
frame.pack()

speed = 0.65
frame.winfo_toplevel().title(f"Speed: {speed}")

def increase_speed():
  global speed
  speed += 0.05
  if speed < 0.0:
    speed = 0.0
  if speed > 1.0:
    speed = 1.0
  print(speed)
  frame.winfo_toplevel().title(f"Speed: {speed}")

def decrease_speed():
  global speed
  speed -= 0.05
  if speed < 0.0:
    speed = 0.0
  if speed > 1.0:
    speed = 1.0
  print(speed)
  frame.winfo_toplevel().title(f"Speed: {speed}")

def up():
  global speed
  print("up")
  os.system(f"mosquitto_pub -t cmd_vel -m '0.0,-{speed}'")

def down():
  global speed
  print("down")
  os.system(f"mosquitto_pub -t cmd_vel -m '0.0,{speed}'")

def left():
  global speed
  print("left")
  os.system(f"mosquitto_pub -t cmd_vel -m '-{speed},0.0'")

def right():
  global speed
  print("right")
  os.system(f"mosquitto_pub -t cmd_vel -m '{speed},0.0'")

def stop():
  global speed
  print("stop")
  os.system(f"mosquitto_pub -t cmd_vel -m '0.0,0.0'")


bottomframe = Frame(root)
bottomframe.pack( side = TOP )

upbutton = Button(frame, text="Up", fg="black", 
  command=up)
upbutton.pack( side = BOTTOM )

slowbutton = Button(frame, text="slow", fg="black",
  command=decrease_speed)
slowbutton.pack( side = LEFT )

fastbutton = Button(frame, text="fast", fg="black",
  command=increase_speed)
fastbutton.pack( side = RIGHT )

leftbutton = Button(bottomframe, text="Left", fg="black",
  command=left)
leftbutton.pack( side = LEFT)

rightbutton = Button(bottomframe, text="Right", fg="black",
  command=right)
rightbutton.pack( side = RIGHT )

downbutton = Button(bottomframe, text="Down", fg="black",
  command=down)
downbutton.pack( side = BOTTOM)

stopframe = Frame(root)
stopframe.pack( side = BOTTOM )
stopbutton = Button(stopframe, text="Stop", fg="red",
  command=stop)
stopbutton.pack( side = BOTTOM)

root.mainloop()

