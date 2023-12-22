import { NativeModules } from 'react-native'
import { TaskInfo } from '..'

const { RNBackgroundDownloader } = NativeModules

function validateHandler (handler) {
  const type = typeof handler

  if (type !== 'function')
    throw new TypeError(`[RNBackgroundDownloader] expected argument to be a function, got: ${type}`)
}

export default class DownloadTask {
  id = ''
  state = 'PENDING'
  metadata = {}

  percent = 0
  bytesDownloaded = 0
  bytesTotal = 0

  constructor (taskInfo: TaskInfo, originalTask?: TaskInfo) {
    this.id = taskInfo.id
    this.percent = taskInfo.percent ?? 0
    this.bytesDownloaded = taskInfo.bytesDownloaded ?? 0
    this.bytesTotal = taskInfo.bytesTotal ?? 0

    const metadata = this.tryParseJson(taskInfo.metadata)
    if (metadata)
      this.metadata = metadata

    if (originalTask) {
      this.beginHandler = originalTask.beginHandler
      this.progressHandler = originalTask.progressHandler
      this.doneHandler = originalTask.doneHandler
      this.errorHandler = originalTask.errorHandler
    }
  }

  begin (handler) {
    validateHandler(handler)
    this.beginHandler = handler
    return this
  }

  progress (handler) {
    validateHandler(handler)
    this.progressHandler = handler
    return this
  }

  done (handler) {
    validateHandler(handler)
    this.doneHandler = handler
    return this
  }

  error (handler) {
    validateHandler(handler)
    this.errorHandler = handler
    return this
  }

  onBegin ({ expectedBytes, headers }) {
    this.state = 'DOWNLOADING'
    this.beginHandler?.({ expectedBytes, headers })
  }

  onProgress (percent, bytesDownloaded, bytesTotal) {
    this.percent = percent
    this.bytesDownloaded = bytesDownloaded
    this.bytesTotal = bytesTotal
    this.progressHandler?.(percent, bytesDownloaded, bytesTotal)
  }

  onDone ({ location }) {
    this.state = 'DONE'
    this.doneHandler?.({ location })
  }

  onError (error, errorCode) {
    this.state = 'FAILED'
    this.errorHandler?.(error, errorCode)
  }

  pause () {
    this.state = 'PAUSED'
    RNBackgroundDownloader.pauseTask(this.id)
  }

  resume () {
    this.state = 'DOWNLOADING'
    RNBackgroundDownloader.resumeTask(this.id)
  }

  stop () {
    this.state = 'STOPPED'
    RNBackgroundDownloader.stopTask(this.id)
  }

  tryParseJson (element) {
    try {
      if (typeof element === 'string')
        element = JSON.parse(element)

      return element
    } catch (e) {
      console.warn('DownloadTask tryParseJson', e)
      return null
    }
  }
}
