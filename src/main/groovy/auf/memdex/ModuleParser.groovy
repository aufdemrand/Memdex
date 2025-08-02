package auf.memdex

class ModuleParser {


    Map<String, Object> parseModule(String scriptContent) {
        def module = [:]
        def functions = [:]
        def moduleVars = [:]
        def currentFunction = null

        scriptContent.eachLine { line ->
            line = line.trim()
            if (!line || line.startsWith('//')) return // Skip empty/comment lines

            // Module Definition
            if (line.startsWith('@')) {
                def matcher = (line =~ /@(\w+)\s*:\s*(.+?)\s*\((.+)\)/)
                if (matcher.find()) {
                    module.putAll([
                            type: matcher.group(1),
                            name: matcher.group(2).trim(),
                            group: matcher.group(3).trim()
                    ])
                }
            }
            // Function Definition
            else if (line.startsWith('#')) {
                currentFunction = line[0..line.indexOf(':')-1] // keep the #, remove the trailing :
                functions[currentFunction] = [name: currentFunction, script: []]
            }
            // Module Var Definition
            else if (line.startsWith('var') && currentFunction == null) {
                def (name, value) = parseVarLine(line)
                moduleVars[name] = value
            }
            // Script line for the current function
            else if (currentFunction) {
                functions[currentFunction].script.add(line)
            }
        }
        module['functions'] = functions
        module['vars'] = moduleVars
        return module
    }

    private parseVarLine(String line) {
        def parts = line.split('=', 2)
        def name = parts[0].replace('var', '').trim()
        def value = parts.size() > 1 ? parts[1].trim() : null

        // Handle array syntax
        if (name.endsWith('[]')) {
            name = name.take(name.size() - 2)
            if (value) {
                // Remove outer quotes and split on pipe
                if (value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1)
                }
                value = value.split('\\|')
            } else {
                value = []
            }
        } else {
            // For non-array vars, remove quotes
            if (value && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1)
            }
        }
        return [name, value]
    }
}