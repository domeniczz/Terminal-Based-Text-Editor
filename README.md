# Terminal-Based-Text-Editor
A Terminal-Based Text Editor (Similar to Vim)

## Environment

Runing on JDK-11

depends [Java Native Access](https://github.com/java-native-access/jna) to call native methods

## Features

- Open given file
- Vertical & Horizontal scrolling
- Search through the whole file
- Support Windows/MacOS/Linux terminal

## Run

Windows Terminal test passed, MacOS and Linux haven't been tested yet

```bash
# go to the script path
cd <filepath>
# run editor
java -cp jna-5.13.0.jar Viewer.java <fileToOpen>
```

## Thanks

Thanks Marco Behler for offering free guide videos
