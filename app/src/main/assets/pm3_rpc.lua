--
-- JSON-line RPC shim, run inside the Proxmark client as:
--
--     proxmark3 <port> -i -c "script run pm3_rpc"
--
-- The client has no machine-readable output mode: it prints for humans and
-- gives no signal for "this command is finished". Driving it by watching for a
-- prompt is unreliable, because command output can contain anything.
--
-- So instead we run a script *inside* the client that owns stdin. It reads one
-- JSON object per line, runs the command through core.console() -- whose output
-- still goes to stdout as usual -- and then prints a framing sentinel. The host
-- treats any line that parses as JSON with a "type" as control, and everything
-- else as command output.
--
-- Ported from nemanjan00/node-proxmark3's interpreter.lua, using the dkjson
-- that already ships in the client's lualibs rather than a vendored copy.
--
local json = require('dkjson')

-- The leading newline matters. The client prints progress with
-- PrintAndLogEx(INPLACE, ...), which emits a carriage return and no trailing
-- newline, so whatever it left on the line would otherwise swallow this
-- object: "[=] Searching...{"type":"command_end"}". The host then never sees
-- the sentinel and waits forever for a command that already finished.
local function emit(obj)
    io.write("\n" .. json.encode(obj) .. "\n")
    io.flush()
end

emit({ type = "started" })

local command
repeat
    local line = io.read()
    if line == nil then break end -- stdin closed: host went away

    local ok, decoded = pcall(json.decode, line)
    if ok and type(decoded) == "table" then
        command = decoded
        if command.type == "command" then
            local ran, err = pcall(core.console, command.command)
            emit({
                type = "command_end",
                id = command.id,
                ok = ran and true or false,
                error = (not ran) and tostring(err) or nil,
            })
        end
    else
        -- Unparseable input would otherwise spin this loop silently.
        emit({ type = "error", error = "malformed request" })
    end
until command ~= nil and command.type == "exit"
