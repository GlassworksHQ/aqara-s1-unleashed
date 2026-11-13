export enum DebugLevel {
    Silent = 0,
    Debug,
    Verbose,
}

let debugLevel = DebugLevel.Silent;

export function logDebug(...data: any[]) {
    if (debugLevel >= 1) {
        console.log(...data);
    }
}

export function logVerbose(...data: any[]) {
    if (debugLevel >= 2) {
        console.log(...data);
    }
}

/**
 * Change the debug level for the fastboot client:
 *   - 0 = silent
 *   - 1 = debug, recommended for general use
 *   - 2 = verbose, for debugging only
 *
 * @param {number} level - Debug level to use.
 */
export function setDebugLevel(level: DebugLevel) {
    debugLevel = level;
}

/**
 * Reads all of the data in the given blob and returns it as an ArrayBuffer.
 *
 * @param {Blob} blob - Blob with the data to read.
 * @returns {Promise<ArrayBuffer>} ArrayBuffer containing data from the blob.
 * @ignore
 */
export function readBlobAsBuffer(blob: Blob): Promise<ArrayBuffer> {
    return blob.arrayBuffer();
}
