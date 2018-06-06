(function () {
    var gui = (function () {
        if (!('WebSocket' in window)) {
            return function () {};
        }

        var open_delay_list = [60, 30, 30, 30, 15, 15, 15, 10, 10, 10, 5, 5, 5, 3, 3, 3, 2, 2, 2, 1, 1, 1],
                open_delay = open_delay_list.length,
                wsUrl = document
                .location.toString().replace(/^http/, 'ws')
                .replace(/[^\/]*$/, 'queue-admin/processes'),
                elements = {},
                actions = {},
                ws;

        var saveData = (function () {
            return function (blob, fileName) {
                var a = document.createElement("a");
                document.body.appendChild(a);
                a.style = "display: none";
                var url = window.URL.createObjectURL(blob);
                a.href = url;
                a.download = fileName;
                a.click();
                window.URL.revokeObjectURL(url);
                document.body.removeChild(a);
            };
        }());


        var error = function (text) {
            var div = document.createElement('div');
            var close = document.createElement('div');
            close.appendChild(document.createTextNode("*"));
            close.setAttribute("class", "close");
            div.appendChild(close);
            div.appendChild(document.createTextNode(text));
            elements.errors.appendChild(div);
            close.onclick = (function (div) {
                return function () {
                    elements.errors.removeChild(div);
                };
            })(div);
            while (elements.errors.childNodes().length > 10) {
                elements.errors.removeChild(elements.errors.firstChild);
            }
            return div;
        };

        var connect = function () {
            ws = new WebSocket(wsUrl);
            ws.onopen = onopen;
            ws.onclose = onclose;
            ws.onerror = onerror;
            ws.onmessage = onmessage;
            ws.binaryType = "blob";
        };
        var onopen = function () {
            open_delay = open_delay_list.length;
            send({action: 'queue-diags'});
        };
        var onclose = function () {
            ws = undefined;
            var delay;
            if (open_delay === 0) {
                delay = open_delay_list[0];
            } else {
                delay = open_delay_list[--open_delay];
            }
            window.setTimeout(connect, 1000 * delay);
        };
        var onerror = function (message) {
            error("WebSocket Error");
        };
        var onmessage = function (e) {
            if (e.data instanceof Blob) {
                saveData(e.data, "full.log");
                return;
            }
            var json = JSON.parse(e.data);
            var action = json.action;
            if (action in actions) {
                actions[action](json);
            }
        };
        var send = function (message) {
            if (ws !== undefined)
                ws.send(JSON.stringify(message));
        };
        var switch_to = function (id) {
            var tab = document.getElementById(id);
            var panel = document.getElementById(id + "-panel");
            if (tab !== null && panel !== null) {
                focus = id;
                send({action: "log", id: id});
                document.querySelectorAll(".tab").forEach(function (e, i) {
                    e.classList.remove('tab-selected');
                });
                document.querySelectorAll(".panel").forEach(function (e, i) {
                    e.classList.remove('panel-selected');
                });
                tab.classList.add('tab-selected');
                panel.classList.add('panel-selected');
            }
        };

        actions.log = function (json) {
            var log = document.getElementById(json.id + "-log");
            if (log !== null) {
                var at_bottom = log.scrollTop === log.scrollTopMax;
                log.appendChild(document.createTextNode(json.message));
                while (log.childNodes.length > 250)
                    log.removeChild(log.firstChild);
                if (at_bottom)
                    log.scrollTop = log.scrollTopMax;
            }
        };
        actions.add = function (json) {
            actions.update(json);
            switch_to(json.id);
        };
        actions.update = function (json) {
            if (document.getElementById(json.id) === null) {
                var firstChild = !elements.tabs.hasChildNodes();
                var button = document.createElement("button");
                button.setAttribute("id", json.id);
                button.setAttribute("class", "tab");
                button.appendChild(document.createTextNode(json.name));
                button.onclick = (function (id) {
                    return function () {
                        switch_to(id);
                    };
                })(json.id);
                elements.tabs.appendChild(button);
                var panel = document.createElement("div");
                panel.setAttribute("id", json.id + "-panel");
                panel.setAttribute("class", "panel");
                elements.panels.appendChild(panel);

                var menu = document.createElement("div");
                menu.setAttribute("id", json.id + "-menu");
                menu.setAttribute("class", "menu");
                panel.appendChild(menu);

                var cancel = document.createElement("button");
                cancel.setAttribute("id", json.id + "-cancel");
                cancel.setAttribute("class", "action");
                cancel.appendChild(document.createTextNode("Cancel"));
                cancel.onclick = (function (id) {
                    return function () {
                        send({action: "cancel", id: id});
                    };
                })(json.id);
                menu.appendChild(cancel);

                var log = document.createElement("div");
                log.setAttribute("id", json.id + "-log");
                log.setAttribute("class", "log");
                panel.appendChild(log);
                if (json.completed) {
                    log.appendChild(document.createTextNode("*JOB COMPLETED*"));
                }
                if (firstChild) {
                    switch_to(json.id);
                }
            }
            var button = document.getElementById(json.id);
            if (json.running)
                button.classList.add("tab-running");
            else
                button.classList.remove("tab-running");
            if (json.alive)
                button.classList.add("tab-alive");
            else
                button.classList.remove("tab-alive");
            if (json.completed)
                button.classList.add("tab-completed");
            else
                button.classList.remove("tab-completed");
            var menu = document.getElementById(json.id + "-menu");
            if (json.started) {
                var started = document.getElementById(json.id + "-started");
                if (started === null) {
                    started = document.createElement("button");
                    started.setAttribute("id", json.id + "-started");
                    started.setAttribute("disabled", "true");
                    started.setAttribute("class", "info");
                    started.appendChild(document.createTextNode(new Date(json.started).toString()));
                    menu.appendChild(started);
                }
            }
            if (json.stopped) {
                var stopped = document.getElementById(json.id + "-stopped");
                if (stopped === null) {
                    stopped = document.createElement("button");
                    stopped.setAttribute("id", json.id + "-stopped");
                    stopped.setAttribute("disabled", "true");
                    stopped.setAttribute("class", "info");
                    stopped.appendChild(document.createTextNode(new Date(json.stopped).toString()));
                    menu.appendChild(stopped);
                    var full_log = document.createElement("button");
                    full_log.setAttribute("id", json.id + "-full_log");
                    full_log.setAttribute("class", "action");
                    full_log.appendChild(document.createTextNode("Full log"));
                    full_log.onclick = (function (id) {
                        return function () {
                            send({action: "full-log", id: id});
                        };
                    })(json.id);
                    menu.appendChild(full_log);
                    var purge = document.createElement("button");
                    purge.setAttribute("id", json.id + "-purge");
                    purge.setAttribute("class", "action");
                    purge.appendChild(document.createTextNode("Purge"));
                    purge.onclick = (function (id) {
                        return function () {
                            send({action: "purge", id: id});
                        };
                    })(json.id);
                    menu.appendChild(purge);
                    var cancel = document.getElementById(json.id + "-cancel");
                    if (cancel !== null)
                        menu.removeChild(cancel);
                }
            }
        };
        actions.remove = function (json) {
            var button = document.getElementById(json.id);
            var panel = document.getElementById(json.id + "-panel");
            if (button !== null) {
                if (json.id === focus) {
                    if (button.nextSibling !== null)
                        switch_to(button.nextSibling.id);
                    else if (button.previousSibling !== null)
                        switch_to(button.previousSibling.id);
                }
                elements.tabs.removeChild(button);
            }
            if (panel !== null)
                elements.panels.removeChild(panel);
        };
        var add_diag = function (diag, message) {
            var div = document.createElement("div");
            var requeue = document.createElement("button");
            requeue.setAttribute("class", "action");
            requeue.appendChild(document.createTextNode("Requeue"));
            requeue.onclick = (function (pattern, div) {
                return function () {
                    send({action: "requeue", pattern: pattern});
                    elements.diags.removeChild(div);
                };
            })(diag, div);
            var list = document.createElement("button");
            list.setAttribute("class", "action");
            list.appendChild(document.createTextNode("List"));
            list.onclick = (function (pattern, div) {
                return function () {
                    send({action: "list", pattern: pattern});
                };
            })(diag, div);
            var discard = document.createElement("button");
            discard.setAttribute("class", "action");
            discard.appendChild(document.createTextNode("Discard"));
            discard.onclick = (function (pattern, div) {
                return function () {
                    send({action: "discard", pattern: pattern});
                    elements.diags.removeChild(div);
                };
            })(diag, div);

            div.appendChild(requeue);
            div.appendChild(list);
            div.appendChild(discard);
            div.appendChild(document.createTextNode(message));
            elements.diags.appendChild(div);
        };
        actions.queue_diags = function (json) {
            while (elements.diags.hasChildNodes()) {
                elements.diags.removeChild(elements.diags.firstChild);
            }
            if (json['diag-count'] === 0) {
                var div = document.createElement("div");
                div.appendChild(document.createTextNode("No errors"));
                elements.diags.appendChild(div);
            } else {
                if ('diag-count-warning' in json) {
                    var div = document.createElement("div");
                    div.appendChild(document.createTextNode(json['diag-count'] + " diags found. " + json['diag-count-warning']));
                    elements.diags.appendChild(div);
                }
                add_diag('*', " * (100%)");
            }
            var count = 0;
            var diags = [];
            for (var diag in json.diag) {
                count += json.diag[diag];
                diags.push(diag);
            }
            diags.sort(function (l, r) {
                return json.diag[r] - json.diag[l];
            });
            diags.forEach(function (diag, idx) {
                add_diag(diag, " " + diag + " (" + Math.round(json.diag[diag] * 100 / count) + "%)");
            });
        };
        return  function () {
            elements.tabs = document.getElementById("tabs");
            elements.panels = document.getElementById("panels");
            elements.diags = document.getElementById("diags");
            elements.errors = document.getElementById("errors");
            elements.refresh = document.getElementById("refresh");
            for (var key in elements) {
                if (elements[key] === null) {
                    window.window.alert("Cannot find document element: " + key);
                }
            }
            elements.refresh.onclick = function () {
                send({action: 'queue-diags'});
            };
            connect();
        };
    })();

    window.addEventListener("load", gui);
})();
