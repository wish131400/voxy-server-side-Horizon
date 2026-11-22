"""Verify that shipping worker pools execute the core policy and expose its controls."""


def verify_core_affinity(archive, class_references):
    prefix = 'dev/xantha/vss/client/prediction/'

    def require(condition, message):
        if not condition:
            raise ValueError('Core affinity: ' + message)

    for cls, method, owner, name in [
        ('PredictionWorkerPool', 'beforeExecute', 'PredictionCoreAffinity', 'applyCurrentWorker'),
        ('PredictionTileManager', '<init>', 'PredictionWorkerPool', '<init>'),
        ('PredictionTileManager', None, 'PredictionCoreAffinity', 'newWorker'),
        ('PredictionMemoryBudget', 'workerCount', 'PredictionCoreAffinity', 'capWorkers'),
        ('PredictionCoreAffinity$Worker', 'refresh', 'PredictionCoreAffinity$Access', 'select'),
    ]:
        refs = class_references(archive.read(prefix + cls + '.class'))
        require(any(ref[:2] == (prefix + owner, name)
                    for (called, _), entries in refs.items() if method is None or called == method for ref in entries),
                cls + '.' + (method or '<worker-factory>') + ' does not execute ' + owner + '.' + name)
    for cls, symbols in [
        ('PredictionWindowsCpuSets', ['GetSystemCpuSetInformation', 'SetThreadSelectedCpuSets', 'GetThreadSelectedCpuSets']),
        ('PredictionCoreAffinity', ['performanceCores', 'efficiencyCores', 'reservedPhysicalCores']),
    ]:
        data = archive.read(prefix + cls + '.class')
        for symbol in symbols:
            require(symbol.encode() in data, 'missing ' + cls + ': ' + symbol)
    ui = archive.read('dev/xantha/vss/client/VSSVoxyOptionsIntegration.class')
    for symbol in ['prediction_core_mode', 'prediction_reserved_cores']:
        require(symbol.encode() in ui, 'missing UI: ' + symbol)
    for locale in ['en_us', 'zh_cn']:
        data = archive.read('assets/vss/lang/' + locale + '.json')
        for symbol in ['core_mode_system', 'core_mode_reserve', 'core_mode_performance', 'core_mode_efficiency']:
            require(symbol.encode() in data, 'missing ' + locale + ' translation: ' + symbol)
