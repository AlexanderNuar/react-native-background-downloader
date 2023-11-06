import React, {useEffect, useState} from 'react';
import {StyleSheet, View, Text, FlatList, Platform} from 'react-native';
import RNFS from 'react-native-fs';
import RNBGD from '@kesha-antonov/react-native-background-downloader';
import Slider from '@react-native-community/slider';
import {ExButton, ExWrapper} from '../../components/commons';
import {toast, uuid} from '../../utils';

const defaultDir = RNBGD.directories.documents;

const Footer = ({
  onStart,
  onStop,
  onReset,
  onClear,
  onRead,
  isStart,
  ...props
}) => {
  return (
    <View style={styles.headerWrapper} {...props}>
      {isStart ? (
        <ExButton title={'Stop'} onPress={onStop} />
      ) : (
        <ExButton title={'Start'} onPress={onStart} />
      )}

      <ExButton title={'Reset'} onPress={onReset} />
      <ExButton title={'Clear'} onPress={onClear} />
      <ExButton title={'Read'} onPress={onRead} />
    </View>
  );
};

const BasicExampleScreen = () => {
  const [urlList] = useState([
    {
      id: uuid(),
      url: 'https://proof.ovh.net/files/100Mb.dat',
    },
    {
      id: uuid(),
      url: 'https://sabnzbd.org/tests/internetspeed/20MB.bin',
    },
    {
      id: uuid(),
      url: 'https://sabnzbd.org/tests/internetspeed/50MB.bin',
    },
  ]);

  const [isStart, setIsStart] = useState(false);

  const [downloadTasks, setDownloadTasks] = useState([]);

  useEffect(() => {
    resumeExistingTasks();
  }, []);

  /**
   * It is used to resume your incomplete or unfinished downloads.
   */
  const resumeExistingTasks = async () => {
    const tasks = await RNBGD.checkForExistingDownloads()
      .then(data => data)
      .catch(err => {
        console.log(`checkForExistingDownloads failed ${err}`);
      });

    console.log(tasks);

    if (tasks.length > 0) {
      tasks.map(task => process(task));
      setDownloadTasks(prevState => [...prevState, ...tasks]);
      setIsStart(true);
    }
  };

  const readStorage = async () => {
    const files = await RNFS.readdir(defaultDir);
    toast('Check logs');
    console.log(`Downloaded files: ${files}`);
  };

  const clearStorage = async () => {
    const files = await RNFS.readdir(defaultDir);

    if (files.length > 0) {
      await Promise.all(
        files.map(file => RNFS.unlink(defaultDir + '/' + file)),
      );
    }

    toast('Check logs');
    console.log(`Deleted file count: ${files.length}`);
  };

  const process = task => {
    const {index} = getTask(task.id);

    return task
      .begin(({expectedBytes, headers}) => {
        setDownloadTasks(prevState => {
          prevState[index] = task;
          return [...prevState];
        });
      })
      .progress((percent, bytesWritten, totalBytes) => {
        setDownloadTasks(prevState => {
          prevState[index] = task;
          return [...prevState];
        });
      })
      .done(() => {
        console.log(`Finished downloading: ${task.id}`);
        setDownloadTasks(prevState => {
          prevState[index] = task;
          return [...prevState];
        });

        Platform.OS === 'ios' && RNBGD.completeHandler(task.id);
      })
      .error(err => {
        console.error(`Download ${task.id} has an error: ${err}`);
        setDownloadTasks(prevState => {
          prevState[index] = task;
          return [...prevState];
        });

        Platform.OS === 'ios' && RNBGD.completeHandler(task.id);
      });
  };

  const reset = () => {
    stop();
    setDownloadTasks([]);
    setIsStart(false);
  };

  const start = () => {
    /**
     * You need to provide the extension of the file in the destination section below.
     * If you cannot provide this, you may experience problems while using your file.
     * For example; Path + File Name + .png
     */
    const taskAttributes = urlList.map(item => {
      const destination = defaultDir + '/' + item.id;
      return {
        id: item.id,
        url: item.url,
        destination: destination,
      };
    });

    const tasks = taskAttributes.map(taskAttribute =>
      process(RNBGD.download(taskAttribute)),
    );

    setDownloadTasks(prevState => [...prevState, ...tasks]);
    setIsStart(true);
  };

  const stop = () => {
    const tasks = downloadTasks.map(task => {
      task.stop();
      return task;
    });

    setDownloadTasks(tasks);
    setIsStart(false);
  };

  const pause = id => {
    const {index, task} = getTask(id);

    task.pause();
    setDownloadTasks(prevState => {
      prevState[index] = task;
      return [...prevState];
    });
  };

  const resume = id => {
    const {index, task} = getTask(id);

    task.resume();
    setDownloadTasks(prevState => {
      prevState[index] = task;
      return [...prevState];
    });
  };

  const cancel = id => {
    const {index, task} = getTask(id);

    task.stop();
    setDownloadTasks(prevState => {
      prevState[index] = task;
      return [...prevState];
    });
  };

  const getTask = id => {
    const index = downloadTasks.findIndex(task => task.id === id);
    const task = downloadTasks[index];
    return {index, task};
  };

  return (
    <ExWrapper>
      <Text style={styles.title}>Basic Example</Text>
      <View>
        <FlatList
          data={urlList}
          keyExtractor={(item, index) => `url-${index}`}
          renderItem={({index, item}) => (
            <View style={styles.item}>
              <View style={styles.itemContent}>
                <Text>Id: {item.id}</Text>
                <Text>Url: {item.url}</Text>
              </View>
            </View>
          )}
          ListFooterComponent={() => (
            <Footer
              isStart={isStart}
              onStart={start}
              onStop={stop}
              onReset={reset}
              onClear={clearStorage}
              onRead={readStorage}
            />
          )}
        />
      </View>
      <FlatList
        style={{flex: 1, flexGrow: 1}}
        data={downloadTasks}
        renderItem={({item, index}) => {
          const isEnd = ['STOPPED', 'DONE', 'FAILED'].includes(item.state);
          const isDownloading = item.state === 'DOWNLOADING';

          return (
            <View style={styles.item}>
              <View style={styles.itemContent}>
                <Text>{item?.id}</Text>
                <Text>{item?.state}</Text>
                <Slider
                  disabled={true}
                  value={item?.bytesWritten}
                  minimumValue={0}
                  maximumValue={item?.totalBytes}
                />
              </View>
              <View>
                {!isEnd &&
                  (isDownloading ? (
                    <ExButton title={'Pause'} onPress={() => pause(item.id)} />
                  ) : (
                    <ExButton
                      title={'Resume'}
                      onPress={() => resume(item.id)}
                    />
                  ))}
                <ExButton title={'Cancel'} onPress={() => cancel(item.id)} />
              </View>
            </View>
          );
        }}
        keyExtractor={(item, index) => `task-${index}`}
      />
    </ExWrapper>
  );
};

const styles = StyleSheet.create({
  headerWrapper: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-evenly',
    padding: 6,
  },
  title: {
    fontSize: 24,
    fontWeight: '500',
    textAlign: 'center',
    alignSelf: 'center',
    marginTop: 16,
  },
  item: {
    padding: 8,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  itemContent: {
    flex: 1,
    flexShrink: 1,
  },
});

export default BasicExampleScreen;
