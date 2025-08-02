package auf.memdex

class ExecutionEngine {
    private final DatabaseService dbService
    private final Map<String, Map> modules // Add reference to all loaded modules

    ExecutionEngine(DatabaseService db, Map<String, Map> modules) {
        this.dbService = db
        this.modules = modules
    }

    String execute(Map module, String functionName, Map context) {
        // Auto-create singleton record if missing (for modules that use their type as UUID)
        if (!context.record) {
            // Check if a record exists in the database with the module type as UUID
            def existing = dbService.getRecord(module.type)
            if (existing) {
                context.record = existing
            } else {
                // Check if there's any record of this module type
                def anyRecord = dbService.findFirstByModuleType(module.type)
                if (anyRecord) {
                    context.record = anyRecord
                } else {
                    // Create new record with module type as UUID and run #construct
                    def uuid = module.type
                    dbService.createRecord(module.type, uuid)
                    def newRecord = dbService.getRecord(uuid)
                    def constructFunc = module.functions['#construct']
                    if (constructFunc) {
                        // Run #construct to initialize vars
                        def tmpContext = [record: newRecord]
                        execute(module, '#construct', tmpContext)
                    }
                    dbService.saveRecord(newRecord)
                    context.record = newRecord
                }
            }
        }

        def function = module.functions[functionName]
        if (!function) return "<h3>Error: Function '${functionName}' not found.</h3>"

        def html = new StringBuilder()
        def record = context.record ?: [data:[:]]
        def recordVars = record.data

        boolean modified = false

        // Track temporary variables for debugging
        def tmpVars = [:]

        // Build a map of select targets from #setup: varName -> arrayVarName
        def selectDefaults = [:]
        if (module.functions['#setup']) {
            module.functions['#setup'].script.each { line ->
                def matcher = (line =~ /select\((\w+),\s*(\w+)\)/)
                if (matcher.find()) {
                    def arrayVar = matcher[0][1]
                    def varName = matcher[0][2]
                    selectDefaults[varName] = arrayVar
                }
            }
        }

        int i = 0
        while (i < function.script.size()) {
            def line = function.script[i]
            // Case/End Case Handler
            if (line.trim().startsWith('case ')) {
                // Only render the first matching case block
                boolean matched = false
                while (i < function.script.size()) {
                    def caseLine = function.script[i]
                    if (caseLine.trim().startsWith('case ')) {
                        def caseMatcher = (caseLine =~ /case (\w+) '(.+)'/)
                        if (caseMatcher.find()) {
                            def varName = caseMatcher[0][1]
                            def matchValue = caseMatcher[0][2]
                            def varValue = recordVars[varName]?.toString() ?: ''
                            if (!matched && varValue ==~ matchValue) {
                                // Render this block
                                i++
                                while (i < function.script.size() &&
                                       !function.script[i].trim().startsWith('case ') &&
                                       function.script[i].trim() != 'end case') {
                                    def blockLine = function.script[i]
                                    // Process blockLine as if it were in the main loop, but do NOT call execute recursively
                                    // Copy-paste the main handler logic here
                                    if (blockLine.startsWith('<<') && blockLine.endsWith('>>')) {
                                        html.append('<div class="incontact-header-row">')
                                        def content = blockLine.substring(2, blockLine.length() - 2)
                                        content.split('\\|').each { cell ->
                                            html.append('<div class="incontact-header-cell">')
                                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                            html.append(cellValue?.toString() ?: '')
                                            html.append('</div>')
                                        }
                                        html.append('</div>')
                                    } else if (blockLine.startsWith('<') && blockLine.endsWith('|')) {
                                        // Multi-line Surround Handler within case block
                                        html.append('<div class="incontact-multiline-container">')

                                        // Process the first line
                                        def content = blockLine.substring(1, blockLine.length() - 1)
                                        html.append('<div class="incontact-row">')
                                        content.split('\\|').each { cell ->
                                            html.append('<div class="incontact-cell">')
                                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                            html.append(cellValue?.toString() ?: '')
                                            html.append('</div>')
                                        }
                                        html.append('</div>')

                                        // Continue processing lines until we find one ending with >
                                        i++
                                        while (i < function.script.size()) {
                                            def nextLine = function.script[i]
                                            if (nextLine.trim().startsWith('case ') || nextLine.trim() == 'end case') {
                                                // Don't consume case/endcase lines, back up one
                                                i--
                                                break
                                            }
                                            if (nextLine.endsWith('>')) {
                                                // This is the final line of the multi-line surround
                                                def finalContent = nextLine.substring(0, nextLine.length() - 1)
                                                html.append('<div class="incontact-row">')
                                                finalContent.split('\\|').each { cell ->
                                                    html.append('<div class="incontact-cell">')
                                                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                                    html.append(cellValue?.toString() ?: '')
                                                    html.append('</div>')
                                                }
                                                html.append('</div>')
                                                break
                                            } else {
                                                // This is a continuation line
                                                html.append('<div class="incontact-row">')
                                                nextLine.split('\\|').each { cell ->
                                                    html.append('<div class="incontact-cell">')
                                                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                                    html.append(cellValue?.toString() ?: '')
                                                    html.append('</div>')
                                                }
                                                html.append('</div>')
                                            }
                                            i++
                                        }

                                        html.append('</div>')
                                    } else if (blockLine.startsWith('<') && blockLine.endsWith('>')) {
                                        html.append('<div class="incontact-row">')
                                        def content = blockLine.substring(1, blockLine.length() - 1)
                                        content.split('\\|').each { cell ->
                                            html.append('<div class="incontact-cell">')
                                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                            html.append(cellValue?.toString() ?: '')
                                            html.append('</div>')
                                        }
                                        html.append('</div>')
                                    } else if (blockLine.startsWith('[') && blockLine.endsWith('|')) {
                                        // Multi-line Block Surround Handler within case block
                                        html.append('<div class="incontact-multiline-container">')

                                        // Process the first line
                                        def content = blockLine.substring(1, blockLine.length() - 1)
                                        html.append('<div class="incontact-block-row">')
                                        content.split('\\|').each { cell ->
                                            html.append('<div class="incontact-block-cell">')
                                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                            html.append(cellValue?.toString() ?: '')
                                            html.append('</div>')
                                        }
                                        html.append('</div>')

                                        // Continue processing lines until we find one ending with ]
                                        i++
                                        while (i < function.script.size()) {
                                            def nextLine = function.script[i]
                                            if (nextLine.trim().startsWith('case ') || nextLine.trim() == 'end case') {
                                                // Don't consume case/endcase lines, back up one
                                                i--
                                                break
                                            }
                                            if (nextLine.endsWith(']')) {
                                                // This is the final line of the multi-line surround
                                                def finalContent = nextLine.substring(0, nextLine.length() - 1)
                                                html.append('<div class="incontact-block-row">')
                                                finalContent.split('\\|').each { cell ->
                                                    html.append('<div class="incontact-block-cell">')
                                                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                                    html.append(cellValue?.toString() ?: '')
                                                    html.append('</div>')
                                                }
                                                html.append('</div>')
                                                break
                                            } else {
                                                // This is a continuation line
                                                html.append('<div class="incontact-block-row">')
                                                nextLine.split('\\|').each { cell ->
                                                    html.append('<div class="incontact-block-cell">')
                                                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                                    html.append(cellValue?.toString() ?: '')
                                                    html.append('</div>')
                                                }
                                                html.append('</div>')
                                            }
                                            i++
                                        }

                                        html.append('</div>')
                                    } else if (blockLine.startsWith('[') && blockLine.endsWith(']')) {
                                        html.append('<div class="incontact-block-row">')
                                        def content = blockLine.substring(1, blockLine.length() - 1)
                                        content.split('\\|').each { cell ->
                                            html.append('<div class="incontact-block-cell">')
                                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                                            html.append(cellValue?.toString() ?: '')
                                            html.append('</div>')
                                        }
                                        html.append('</div>')
                                    } else if (blockLine.trim().startsWith('foreach ')) {
                                        def matcher = (blockLine =~ /foreach (\w+)#(\w+)/)
                                        if (matcher.find()) {
                                            def arrayVar = matcher[0][1]
                                            def funcName = '#' + matcher[0][2]
                                            def uuids = recordVars[arrayVar] ?: []
                                            uuids.each { uuid ->
                                                def rec = dbService.getRecord(uuid)
                                                if (rec) {
                                                    def recModuleType = rec.module_type
                                                    def recModule = modules[recModuleType]
                                                    if (recModule && recModule.functions[funcName]) {
                                                        def ctx = [record: rec]
                                                        html.append('<div class="foreach-item">')
                                                        html.append(execute(recModule, funcName, ctx))
                                                        html.append('</div>')
                                                    } else {
                                                        html.append("<h3>Error: Function '${funcName}' not found in module '${recModuleType}'.</h3>")
                                                    }
                                                }
                                            }
                                        }
                                    } else if (blockLine.trim().startsWith('var ')) {
                                        def matcher = (blockLine =~ /var (\w+)(\[\])?\s*=\s*(.+)/)
                                        if (matcher.find()) {
                                            def varNamee = matcher[0][1]
                                            def isArray = matcher[0][2] != null
                                            def value = matcher[0][3].trim()
                                            if (isArray) {
                                                // Parse array value (remove quotes, split by |)
                                                if (value.startsWith("'")) value = value.substring(1, value.length()-1)
                                                // Unescape escaped single quotes (\')
                                                value = value.replaceAll("\\\\'", "'")
                                                recordVars[varNamee] = value.split('\\|')
                                            } else {
                                                if (value.startsWith("'")) value = value.substring(1, value.length()-1)
                                                // Unescape escaped single quotes (\')
                                                value = value.replaceAll("\\\\'", "'")
                                                // If value is '...' and a select() exists for this var, use the first value of the corresponding array
                                                if (value == '...' && selectDefaults.containsKey(varNamee)) {
                                                    def arrName = selectDefaults[varNamee]
                                                    def arr = module.vars[arrName]
                                                    if (arr instanceof List && arr.size() > 0) {
                                                        value = arr[0]
                                                    }
                                                }
                                                recordVars[varNamee] = value
                                            }
                                            modified = true
                                        }
                                    }
                                    i++
                                }
                                matched = true
                            } else {
                                // Skip this block
                                i++
                                while (i < function.script.size() &&
                                       !function.script[i].trim().startsWith('case ') &&
                                       function.script[i].trim() != 'end case') {
                                    i++
                                }
                            }
                        } else {
                            i++
                        }
                    } else if (caseLine.trim() == 'end case') {
                        i++
                        break
                    } else {
                        i++
                    }
                }
                continue
            }

            // Header Surround Handler: << ... >> (check this FIRST before single <)
            if (line.startsWith('<<') && line.endsWith('>>')) {
                html.append('<div class="incontact-header-row">')
                def content = line.substring(2, line.length() - 2)
                content.split('\\|').each { cell ->
                    html.append('<div class="incontact-header-cell">')
                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                    html.append(cellValue?.toString() ?: '')
                    html.append('</div>')
                }
                html.append('</div>')
            }

            // Multi-line Surround Handler: < ... | (starts multi-line, continues until line ending with >)
            else if (line.startsWith('<') && line.endsWith('|')) {
                html.append('<div class="incontact-multiline-container">')
                
                // Process the first line
                def content = line.substring(1, line.length() - 1)
                html.append('<div class="incontact-row">')
                content.split('\\|').each { cell ->
                    html.append('<div class="incontact-cell">')
                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                    html.append(cellValue?.toString() ?: '')
                    html.append('</div>')
                }
                html.append('</div>')
                
                // Continue processing lines until we find one ending with >
                i++
                while (i < function.script.size()) {
                    def nextLine = function.script[i]
                    if (nextLine.endsWith('>')) {
                        // This is the final line of the multi-line surround
                        def finalContent = nextLine.substring(0, nextLine.length() - 1)
                        html.append('<div class="incontact-row">')
                        finalContent.split('\\|').each { cell ->
                            html.append('<div class="incontact-cell">')
                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                            html.append(cellValue?.toString() ?: '')
                            html.append('</div>')
                        }
                        html.append('</div>')
                        break
                    } else {
                        // This is a continuation line
                        html.append('<div class="incontact-row">')
                        nextLine.split('\\|').each { cell ->
                            html.append('<div class="incontact-cell">')
                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                            html.append(cellValue?.toString() ?: '')
                            html.append('</div>')
                        }
                        html.append('</div>')
                    }
                    i++
                }
                
                html.append('</div>')
            }

            // Surround Handler: < ... >
            else if (line.startsWith('<') && line.endsWith('>')) {
                html.append('<div class="incontact-row">')
                def content = line.substring(1, line.length() - 1)
                content.split('\\|').each { cell ->
                    html.append('<div class="incontact-cell">')
                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                    html.append(cellValue?.toString() ?: '')
                    html.append('</div>')
                }
                html.append('</div>')
            }

            // Multi-line Block Surround Handler: [ ... | (starts multi-line, continues until line ending with ])
            else if (line.startsWith('[') && line.endsWith('|')) {
                html.append('<div class="incontact-multiline-container">')
                
                // Process the first line
                def content = line.substring(1, line.length() - 1)
                html.append('<div class="incontact-block-row">')
                content.split('\\|').each { cell ->
                    html.append('<div class="incontact-block-cell">')
                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                    html.append(cellValue?.toString() ?: '')
                    html.append('</div>')
                }
                html.append('</div>')
                
                // Continue processing lines until we find one ending with ]
                i++
                while (i < function.script.size()) {
                    def nextLine = function.script[i]
                    if (nextLine.endsWith(']')) {
                        // This is the final line of the multi-line surround
                        def finalContent = nextLine.substring(0, nextLine.length() - 1)
                        html.append('<div class="incontact-block-row">')
                        finalContent.split('\\|').each { cell ->
                            html.append('<div class="incontact-block-cell">')
                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                            html.append(cellValue?.toString() ?: '')
                            html.append('</div>')
                        }
                        html.append('</div>')
                        break
                    } else {
                        // This is a continuation line
                        html.append('<div class="incontact-block-row">')
                        nextLine.split('\\|').each { cell ->
                            html.append('<div class="incontact-block-cell">')
                            def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                            html.append(cellValue?.toString() ?: '')
                            html.append('</div>')
                        }
                        html.append('</div>')
                    }
                    i++
                }
                
                html.append('</div>')
            }

            // Block Surround Handler: [ ... ]
            else if (line.startsWith('[') && line.endsWith(']')) {
                html.append('<div class="incontact-block-row">')
                def content = line.substring(1, line.length() - 1)
                content.split('\\|').each { cell ->
                    html.append('<div class="incontact-block-cell">')
                    def cellValue = resolveValue(cell.trim(), module, recordVars, context)
                    html.append(cellValue?.toString() ?: '')
                    html.append('</div>')
                }
                html.append('</div>')
            }

            // Foreach Handler: foreach array_var#function
            else if (line.trim().startsWith('foreach ')) {
                def matcher = (line =~ /foreach (\w+)#(\w+)/)
                if (matcher.find()) {
                    def arrayVar = matcher[0][1]
                    def funcName = '#' + matcher[0][2]
                    def uuids = tmpVars[arrayVar] ?: recordVars[arrayVar] ?: []
                    uuids.each { uuid ->
                        def rec = dbService.getRecord(uuid)
                        if (rec) {
                            def recModuleType = rec.module_type
                            def recModule = modules[recModuleType]
                            if (recModule && recModule.functions[funcName]) {
                                def ctx = [record: rec]
                                html.append('<div class="foreach-item">')
                                html.append(execute(recModule, funcName, ctx))
                                html.append('</div>')
                            } else {
                                html.append("<h3>Error: Function '${funcName}' not found in module '${recModuleType}'.</h3>")
                            }
                        }
                    }
                }
            }

            // Record var assignment handler (var var_name = value)
            else if (line.trim().startsWith('var ')) {
                def matcher = (line =~ /var (\w+)(\[\])?\s*=\s*(.+)/)
                if (matcher.find()) {
                    def varName = matcher[0][1]
                    def isArray = matcher[0][2] != null
                    def value = matcher[0][3].trim()
                    if (isArray) {
                        // Parse array value (remove quotes, split by |)
                        if (value.startsWith("'")) value = value.substring(1, value.length()-1)
                        // Unescape escaped single quotes (\')
                        value = value.replaceAll("\\\\'", "'")
                        recordVars[varName] = value.split('\\|')
                    } else {
                        if (value.startsWith("'")) value = value.substring(1, value.length()-1)
                        // Unescape escaped single quotes (\')
                        value = value.replaceAll("\\\\'", "'")
                        // If value is '...' and a select() exists for this var, use the first value of the corresponding array
                        if (value == '...' && selectDefaults.containsKey(varName)) {
                            def arrName = selectDefaults[varName]
                            def arr = module.vars[arrName]
                            if (arr instanceof List && arr.size() > 0) {
                                value = arr[0]
                            }
                        }
                        recordVars[varName] = value
                    }
                    modified = true
                }
            }

            // Temporary var assignment handler (tmp var_name = value)
            else if (line.trim().startsWith('tmp ')) {
                def matcher = (line =~ /tmp (\w+)(\[\])?\s*=\s*(.+)/)
                if (matcher.find()) {
                    def varName = matcher[0][1]
                    def isArray = matcher[0][2] != null
                    def valueExpr = matcher[0][3].trim()

                    def resolvedValue = resolveValue(valueExpr, module, recordVars, context, tmpVars)
                    tmpVars[varName] = resolvedValue

                    // Debug output for temporary variables
                    println "DEBUG: tmp ${varName} = ${resolvedValue} (${resolvedValue?.getClass()?.simpleName})"
                }
            }

            // Remove handler: remove value
            else if (line.trim().startsWith('remove ')) {
                def matcher = (line =~ /remove (.+)/)
                if (matcher.find()) {
                    def valueExpr = matcher[0][1].trim()
                    def uuidToRemove = resolveValue(valueExpr, module, recordVars, context)

                    // Remove HTML tags if present (in case resolveValue returned formatted content)
                    uuidToRemove = uuidToRemove.replaceAll('<[^>]*>', '').trim()

                    if (uuidToRemove && uuidToRemove != '') {
                        try {
                            dbService.deleteRecord(uuidToRemove)
                            // Remove handler executes silently - no output to screen
                        } catch (Exception e) {
                            // Silent execution - errors are not displayed
                        }
                    }
                }
            }
            i++
        }
        // Save record if modified
        if (modified && record.uuid) {
            dbService.saveRecord(record)
        }
        return html.toString()
    }

    /**
     * Resolves a value from a script cell, parsing resolvers like focus() or variables.
     */
    private Object resolveValue(String value, Map module, Map recordVars, Map context) {
        return resolveValue(value, module, recordVars, context, [:])
    }

    private Object resolveValue(String value, Map module, Map recordVars, Map context, Map tmpVars) {
        // Unwrap quoted string if present
        if (value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1)
        }
        // Unescape escaped single quotes (\')
        value = value.replaceAll("\\\\'", "'")

        // Handle querystring parameter references (&param_name)
        if (value.startsWith('&')) {
            def paramName = value.substring(1) // Remove the & prefix
            if (context.querystring && context.querystring.containsKey(paramName)) {
                return context.querystring[paramName]
            }
            return '' // Return empty string if parameter not found
        }

        // Check temporary variables first
        if (tmpVars.containsKey(value)) {
            def tmpValue = tmpVars[value]
            // If it's a List, return it as-is for assignment operations
            if (tmpValue instanceof List) {
                return tmpValue
            }
            return tmpValue
        }

        // focus(var_name) resolver
        def focusMatcher = (value =~ /focus\((\w+)\)/)
        if (focusMatcher.find()) {
            def varName = focusMatcher[0][1]
            def varValue = recordVars[varName] ?: ''
            // Add data attributes for our JavaScript to use
            return "<span class=\"focusable\" data-varname=\"${varName}\" data-uuid=\"${context.record?.uuid}\">${varValue}</span>"
        }

        // button('label', #function) resolver
        def buttonMatcher = (value =~ /button\('(.+?)',\s*([\w_]+)?#(\w+)\)/)
        if (buttonMatcher.find()) {
            def label = buttonMatcher[0][1]
            def targetModule = buttonMatcher[0][2] ?: module.type
            def function = buttonMatcher[0][3]
            // Use singleton uuid if present, else try to get uuid from context
            def uuid = (targetModule == module.type) ? context.record?.uuid : targetModule
            def url = "/module/${targetModule}/${function}?uuid=${uuid}"
            return "<a href=\"${url}\" class=\"button\">${label}</a>"
        }

        // select(array_var, var_name) resolver
        def selectMatcher = (value =~ /select\(\s*(\w+)\s*,\s*(\w+)\s*\)/)
        if (selectMatcher.find()) {
            def arrayVarName = selectMatcher[0][1]
            def varName = selectMatcher[0][2]
            def options = []

            // Try recordVars first, then module vars
            if (recordVars.containsKey(arrayVarName)) {
                options = recordVars[arrayVarName]
            } else if (module.vars.containsKey(arrayVarName)) {
                options = module.vars[arrayVarName]
            }

            def selected = recordVars[varName] ?: ''
            def uuid = context.record?.uuid
            def selectHtml = "<select class=\"selectable\" data-varname=\"${varName}\" data-uuid=\"${uuid}\">"

            // Add blank default option
            def blankSelected = (selected == '' || selected == null) ? 'selected' : ''
            selectHtml += "<option value=\"\" ${blankSelected}></option>"

            // Add all other options
            options.each { opt ->
                def sel = (opt == selected) ? 'selected' : ''
                selectHtml += "<option value=\"${opt}\" ${sel}>${opt}</option>"
            }
            selectHtml += "</select>"
            return selectHtml
        }

        // back_button('label', [number]) resolver
        def backButtonMatcher = (value =~ /back_button\('(.+?)'(?:,\s*(\d+))?\)/)
        if (backButtonMatcher.find()) {
            def label = backButtonMatcher[0][1]
            def number = backButtonMatcher[0][2] ?: '1'
            // Render a button with a data attribute for JS to handle history.back
            return "<button class=\"back-button\" data-back=\"${number}\">${label}</button>"
        }

        // construction_button('label', #function, [mod_type], [storage_array_var]) resolver
        def constructionButtonMatcher = (value =~ /construction_button\('(.+?)',\s*(#\w+)(?:,\s*(\w+))?(?:,\s*(add:)?(\w+))?\)/)
        if (constructionButtonMatcher.find()) {
            def label = constructionButtonMatcher[0][1]
            def function = constructionButtonMatcher[0][2]
            def modType = constructionButtonMatcher[0][3] ?: module.type
            def addPrefix = constructionButtonMatcher[0][4] // "add:" prefix if present
            def storageArray = constructionButtonMatcher[0][5] // array variable to store UUID

            // Build URL with construction parameters
            def url = "/construct/${modType}/${function.substring(1)}"
            if (context.record?.uuid && storageArray) {
                url += "?parent_uuid=${context.record.uuid}&storage_var=${storageArray}"
            }

            return "<a href=\"${url}\" class=\"construction-button\">${label}</a>"
        }

        // match(source_array,field_name,'pattern') resolver
        def matchMatcher = (value =~ /match\((\w+),\s*(\w+),\s*'(.+)'\)/)
        if (matchMatcher.find()) {
            def arrayVarName = matchMatcher[0][1]
            def fieldName = matchMatcher[0][2]
            def pattern = matchMatcher[0][3]

            def sourceArray = tmpVars[arrayVarName] ?: recordVars[arrayVarName] ?: []
            def matches = []

            sourceArray.each { uuid ->
                def record = dbService.getRecord(uuid?.toString())
                if (record?.data[fieldName]?.toString() == pattern) {
                    matches << uuid
                }
            }

            println "DEBUG: match(${arrayVarName},${fieldName},'${pattern}') found ${matches.size()} matches from ${sourceArray.size()} records"
            return matches
        }

        // sort(source_array,key_var,direction) resolver
        def sortMatcher = (value =~ /sort\((\w+),\s*(\w+),\s*(\w+)\)/)
        if (sortMatcher.find()) {
            def arrayVarName = sortMatcher[0][1]
            def keyVar = sortMatcher[0][2]
            def direction = sortMatcher[0][3].toUpperCase()

            def sourceArray = tmpVars[arrayVarName] ?: recordVars[arrayVarName] ?: []

            println "DEBUG: sort() - attempting to sort variable '${arrayVarName}' with value: ${sourceArray} (${sourceArray?.getClass()?.simpleName})"

            // Ensure we have a list to sort
            if (!(sourceArray instanceof List)) {
                println "DEBUG: sort() - sourceArray is not a List, it's ${sourceArray?.getClass()?.simpleName}: ${sourceArray}"
                return []
            }

            def sortedArray = sourceArray.sort { uuid ->
                def record = dbService.getRecord(uuid?.toString())
                return record?.data[keyVar]?.toString() ?: ''
            }

            if (direction == 'DESCENDING') {
                sortedArray = sortedArray.reverse()
            }

            println "DEBUG: sort(${arrayVarName},${keyVar},${direction}) sorted ${sortedArray.size()} records"
            return sortedArray
        }

        // Plain string literal
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1)
        }

        // A variable from the record
        if (recordVars.containsKey(value)) {
            def recordValue = recordVars[value]
            // If it's a List, return it as-is for assignment operations
            if (recordValue instanceof List) {
                return recordValue
            }
            return recordValue
        }

        // A variable from the module
        if (module.vars.containsKey(value)) {
            return module.vars[value]
        }

        // find_records_by_type(module_type) resolver
        def findRecordsByTypeMatcher = (value =~ /find_records_by_type\((\w+)\)/)
        if (findRecordsByTypeMatcher.find()) {
            def moduleType = findRecordsByTypeMatcher[0][1]
            def uuids = []

            // Get all records of the specified module type
            def dbDir = new File('database')
            if (dbDir.exists()) {
                dbDir.eachFileMatch(~/.*\.json/) { file ->
                    try {
                        def record = new groovy.json.JsonSlurper().parse(file)
                        if (record.module_type == moduleType) {
                            uuids << record.uuid
                        }
                    } catch (Exception e) {
                        // Skip malformed files
                    }
                }
            }

            return uuids
        }

        return value // Return as is if no resolver matches
    }
}
