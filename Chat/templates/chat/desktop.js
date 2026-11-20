/*
 * AI云 桌面版桥接层。
 *
 * 桌面端和安卓端共用同一份 index.html：安卓端由原生 TTS / 原生弹窗提供能力，
 * 桌面端由这里的脚本用浏览器 API + 本地服务补齐，功能保持一致。
 */
(function () {
  if (window.Android) return;

  var LS = window.localStorage;
  var MAX_STORY = 1000;
  var MAX_PRESETS = 5;

  function readJSON(key, fallback) {
    try {
      var raw = LS.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (e) {
      return fallback;
    }
  }

  function writeJSON(key, value) {
    try { LS.setItem(key, JSON.stringify(value)); } catch (e) { /* 存储满时忽略 */ }
  }

  function fmt1(value) {
    return (Math.round((value || 0) * 10) / 10).toFixed(1);
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  // ---------------- 角色（存在浏览器本地） ----------------

  function loadRoles() {
    var list = readJSON('desktop_roles', []);
    return Array.isArray(list) ? list : [];
  }

  function saveRoles(list) { writeJSON('desktop_roles', list); }

  function activeRoleId() { return LS.getItem('desktop_active_role') || ''; }

  function setActiveRole(id) {
    if (id) { LS.setItem('desktop_active_role', id); } else { LS.removeItem('desktop_active_role'); }
  }

  function findRole(id) {
    if (!id) return null;
    var list = loadRoles();
    for (var i = 0; i < list.length; i++) {
      if (list[i] && list[i].id === id) return list[i];
    }
    return null;
  }

  // ---------------- 通用弹窗 ----------------

  var MODAL_CSS = [
    '.desk-mask{position:fixed;inset:0;background:rgba(15,20,30,.45);display:flex;',
    'align-items:center;justify-content:center;z-index:9999;padding:16px;}',
    '.desk-modal{width:100%;max-width:430px;max-height:84vh;background:#fff;border-radius:14px;',
    'display:flex;flex-direction:column;overflow:hidden;box-shadow:0 18px 50px rgba(0,0,0,.22);',
    'font-size:14px;color:#1f2329;}',
    '.desk-head{padding:16px 18px 10px;font-size:16px;font-weight:600;}',
    '.desk-body{padding:4px 18px 12px;overflow-y:auto;}',
    '.desk-foot{display:flex;gap:8px;justify-content:flex-end;padding:10px 18px 16px;}',
    '.desk-row{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:10px;',
    'cursor:pointer;border:1px solid #eef0f3;margin-bottom:8px;}',
    '.desk-row.active{background:#f2f6ff;border-color:#c9d8ff;}',
    '.desk-row-main{flex:1;white-space:pre-line;line-height:1.5;color:#4e5969;}',
    '.desk-row.active .desk-row-main{color:#1f2329;}',
    '.desk-label{padding:12px 0 4px;font-size:13px;color:#4e5969;}',
    '.desk-hint{font-size:12px;color:#8a9099;line-height:1.5;padding:2px 0 8px;}',
    '.desk-input{width:100%;padding:9px 10px;border:1px solid #d9dde3;border-radius:8px;',
    'font:inherit;font-size:14px;outline:none;background:#fff;}',
    '.desk-input:focus{border-color:#3370ff;}',
    '.desk-textarea{resize:vertical;min-height:90px;line-height:1.5;}',
    '.desk-radios{display:flex;gap:16px;padding:2px 0 4px;}',
    '.desk-radio{display:flex;align-items:center;gap:4px;font-size:14px;cursor:pointer;}',
    '.desk-btn{border:none;border-radius:8px;padding:8px 14px;font-size:13px;cursor:pointer;',
    'background:#f2f3f5;color:#1f2329;}',
    '.desk-btn.primary{background:#3370ff;color:#fff;}',
    '.desk-btn.danger{background:transparent;color:#d4380d;padding:4px 6px;}',
    '.desk-btn.plain{background:transparent;color:#3370ff;padding:4px 6px;}',
    '.desk-btn:disabled{opacity:.5;cursor:default;}'
  ].join('');

  var styleInjected = false;

  function ensureStyle() {
    if (styleInjected) return;
    var style = document.createElement('style');
    style.textContent = MODAL_CSS;
    document.head.appendChild(style);
    styleInjected = true;
  }

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  }

  function button(label, cls, onClick) {
    var node = el('button', 'desk-btn' + (cls ? ' ' + cls : ''), label);
    node.type = 'button';
    node.addEventListener('click', onClick);
    return node;
  }

  function openModal(title, build) {
    ensureStyle();
    var old = document.querySelector('.desk-mask');
    if (old) old.remove();

    var mask = el('div', 'desk-mask');
    var box = el('div', 'desk-modal');
    var head = el('div', 'desk-head', title);
    var body = el('div', 'desk-body');
    var foot = el('div', 'desk-foot');
    box.append(head, body, foot);
    mask.appendChild(box);
    mask.addEventListener('click', function (event) {
      if (event.target === mask) mask.remove();
    });
    document.body.appendChild(mask);
    build(body, foot, function () { mask.remove(); });
  }

  // ---------------- 角色管理 ----------------

  function showRoles() {
    openModal('角色', function (body, foot, close) {
      var active = activeRoleId();
      body.appendChild(el('div', 'desk-hint', '点角色即可切换，每个角色有独立的聊天记录。'));

      var defaultRow = el('div', 'desk-row' + (active ? '' : ' active'));
      defaultRow.appendChild(el('div', 'desk-row-main',
        '小云（默认）' + (active ? '' : '（使用中）') + '\n系统默认角色，名字不可更改'));
      defaultRow.addEventListener('click', function () {
        setActiveRole('');
        close();
        location.reload();
      });
      body.appendChild(defaultRow);

      var roles = loadRoles();
      roles.forEach(function (role, index) {
        var rowEl = el('div', 'desk-row' + (role.id === active ? ' active' : ''));
        var main = el('div', 'desk-row-main');
        var label = role.name + (role.gender ? '（' + role.gender + '）' : '')
          + (role.id === active ? '（使用中）' : '');
        var preview = role.story
          ? (role.story.length > 24 ? role.story.slice(0, 24) + '…' : role.story)
          : '未填写设定';
        main.textContent = label + '\n' + preview;
        main.addEventListener('click', function () {
          setActiveRole(role.id);
          close();
          location.reload();
        });
        rowEl.append(
          main,
          button('编辑', 'plain', function (event) {
            event.stopPropagation();
            showRoleEditor(role, index);
          }),
          button('删除', 'danger', function (event) {
            event.stopPropagation();
            if (!confirm('确定删除「' + role.name + '」吗？')) return;
            var list = loadRoles();
            list.splice(index, 1);
            saveRoles(list);
            if (role.id === activeRoleId()) setActiveRole('');
            close();
            location.reload();
          })
        );
        body.appendChild(rowEl);
      });

      foot.appendChild(button('＋ 创建角色', 'primary', function () {
        showRoleEditor(null, -1);
      }));
      foot.appendChild(button('关闭', '', close));
    });
  }

  function showRoleEditor(existing, index) {
    var isNew = !existing;
    openModal(isNew ? '创建角色' : '编辑角色', function (body, foot, close) {
      body.appendChild(el('div', 'desk-label', '名字'));
      var nameInput = document.createElement('input');
      nameInput.className = 'desk-input';
      nameInput.maxLength = 20;
      nameInput.placeholder = '角色名字，最多 20 个字';
      if (!isNew) nameInput.value = existing.name;
      body.appendChild(nameInput);

      body.appendChild(el('div', 'desk-label', '性别'));
      var genderWrap = el('div', 'desk-radios');
      var chosen = isNew ? '女' : (existing.gender || '女');
      ['女', '男', '其他'].forEach(function (gender) {
        var label = el('label', 'desk-radio');
        var input = document.createElement('input');
        input.type = 'radio';
        input.name = 'desk-gender';
        input.value = gender;
        input.checked = gender === chosen;
        input.addEventListener('change', function () {
          if (input.checked) chosen = gender;
        });
        label.append(input, el('span', null, gender));
        genderWrap.appendChild(label);
      });
      body.appendChild(genderWrap);

      body.appendChild(el('div', 'desk-label', '故事 / 角色设定（最多 ' + MAX_STORY + ' 字）'));
      var story = document.createElement('textarea');
      story.className = 'desk-input desk-textarea';
      story.maxLength = MAX_STORY;
      story.placeholder = '写下角色的背景故事、性格、说话方式…';
      if (!isNew) story.value = existing.story || '';
      body.appendChild(story);

      var counter = el('div', 'desk-hint', story.value.length + '/' + MAX_STORY);
      story.addEventListener('input', function () {
        counter.textContent = story.value.length + '/' + MAX_STORY;
      });
      body.appendChild(counter);

      foot.appendChild(button('取消', '', close));
      foot.appendChild(button('保存', 'primary', function () {
        var name = nameInput.value.trim();
        if (!name) {
          nameInput.focus();
          return;
        }
        var list = loadRoles();
        var data = {
          id: isNew ? 'r' + Date.now() : existing.id,
          name: name,
          gender: chosen,
          story: story.value.trim().slice(0, MAX_STORY)
        };
        if (isNew) {
          list.push(data);
          setActiveRole(data.id);
        } else {
          list[index] = data;
        }
        saveRoles(list);
        close();
        location.reload();
      }));
    });
  }

  // ---------------- 音色与朗读 ----------------

  function loadPresets() {
    var list = readJSON('desktop_voice_presets', []);
    return Array.isArray(list) ? list : [];
  }

  function savePresets(list) { writeJSON('desktop_voice_presets', list); }

  function activeVoiceIndex() {
    var raw = LS.getItem('desktop_voice_active');
    if (raw === null || raw === '') return -1;
    var index = parseInt(raw, 10);
    return isNaN(index) ? -1 : index;
  }

  function setActiveVoiceIndex(index) {
    if (index < 0) { LS.removeItem('desktop_voice_active'); } else { LS.setItem('desktop_voice_active', String(index)); }
  }

  function activePreset() {
    var list = loadPresets();
    var index = activeVoiceIndex();
    return index >= 0 && index < list.length ? list[index] : null;
  }

  var cachedVoices = [];

  function refreshVoices() {
    if (!window.speechSynthesis) return;
    try { cachedVoices = speechSynthesis.getVoices() || []; } catch (e) { cachedVoices = []; }
  }

  if (window.speechSynthesis) {
    refreshVoices();
    speechSynthesis.onvoiceschanged = refreshVoices;
  }

  function zhVoices() {
    refreshVoices();
    return cachedVoices.filter(function (voice) {
      return (voice.lang || '').toLowerCase().indexOf('zh') === 0;
    });
  }

  function genderFactors() {
    var role = findRole(activeRoleId());
    if (!role) return [1, 1];
    if (role.gender === '男') return [0.78, 0.96];
    if (role.gender === '女') return [1.12, 1.03];
    return [1, 1];
  }

  function splitForSpeech(text) {
    var parts = [];
    var buffer = '';
    for (var i = 0; i < text.length; i++) {
      var ch = text.charAt(i);
      buffer += ch;
      if ('。！？!?；;\n'.indexOf(ch) >= 0 || buffer.length >= 40) {
        var part = buffer.trim();
        if (part) parts.push(part);
        buffer = '';
      }
    }
    if (buffer.trim()) parts.push(buffer.trim());
    return parts;
  }

  function pauseAfter(part) {
    if (!part) return 120;
    var last = part.charAt(part.length - 1);
    var base;
    if ('。！？!?'.indexOf(last) >= 0) { base = 280; } else if ('；;'.indexOf(last) >= 0) { base = 210; } else if ('，,、'.indexOf(last) >= 0) { base = 110; } else { base = 160; }
    return Math.max(60, base + Math.round(Math.random() * 120) - 40);
  }

  function containsAny(text) {
    for (var i = 1; i < arguments.length; i++) {
      if (text.indexOf(arguments[i]) >= 0) return true;
    }
    return false;
  }

  function emotionParams(part, basePitch, baseRate) {
    var pitch = basePitch;
    var rate = baseRate;
    if (containsAny(part, '哈哈', '笑死', '嘿嘿', '好耶', '冲', '😂', '🤣', '😄')) {
      pitch += 0.14; rate += 0.08;
    } else if (containsAny(part, '呜呜', '难过', '委屈', '想哭', '😭', '🥺')) {
      pitch -= 0.10; rate -= 0.08;
    } else if (containsAny(part, '哼', '生气', '讨厌', '烦', '😤', '😡')) {
      pitch += 0.06; rate += 0.05;
    } else if (containsAny(part, '震惊', '卧槽', '离谱', '不会吧', '😱', '😨')) {
      pitch += 0.10; rate += 0.03;
    } else if (containsAny(part, '害羞', '脸红', '😳', '🙈')) {
      pitch += 0.05; rate -= 0.03;
    }
    if (part.charAt(part.length - 1) === '？' || part.charAt(part.length - 1) === '?') {
      pitch += 0.08;
    }
    pitch += (Math.random() - 0.5) * 0.04;
    return [clamp(pitch, 0.3, 2.0), clamp(rate, 0.4, 2.0)];
  }

  var SAMPLE_POOL = [
    '今天的天气很好，适合出门散散步。',
    '春眠不觉晓，处处闻啼鸟，夜来风雨声。',
    '山重水复疑无路，柳暗花明又一村。',
    '海内存知己，天涯若比邻，无为在歧路。',
    '读书破万卷，下笔如有神，愿君多采撷。',
    '生活不止眼前的苟且，还有诗和远方。'
  ];

  function randomSample() {
    var text = SAMPLE_POOL[Math.floor(Math.random() * SAMPLE_POOL.length)];
    return text.length > 15 ? text.slice(0, 15) : text;
  }

  /** 逐句朗读：句间停顿、语调起伏、自定义角色按性别变声，小云保持原音色。 */
  function readAloud(text, presetOverride) {
    if (!text) return;
    if (!window.speechSynthesis) {
      alert('当前浏览器不支持语音朗读，请换用 Chrome / Edge / Safari。');
      return;
    }
    var preset = presetOverride || activePreset();
    var base = preset ? [preset.pitch || 1, preset.rate || 1] : [1, 1];
    var factors = genderFactors();
    var basePitch = clamp(base[0] * factors[0], 0.3, 2.0);
    var baseRate = clamp(base[1] * factors[1], 0.4, 2.0);

    var voice = null;
    if (preset && preset.voice) {
      refreshVoices();
      for (var i = 0; i < cachedVoices.length; i++) {
        if (cachedVoices[i].name === preset.voice) {
          voice = cachedVoices[i];
          break;
        }
      }
    }

    var parts = splitForSpeech(text);
    if (!parts.length) return;

    try { speechSynthesis.cancel(); } catch (e) { /* 忽略 */ }

    function play(index) {
      if (index >= parts.length) return;
      var params = emotionParams(parts[index], basePitch, baseRate);
      var utterance = new SpeechSynthesisUtterance(parts[index]);
      utterance.pitch = params[0];
      utterance.rate = params[1];
      utterance.lang = 'zh-CN';
      if (voice) utterance.voice = voice;
      if (index < parts.length - 1) {
        utterance.onend = function () {
          setTimeout(function () { play(index + 1); }, pauseAfter(parts[index]));
        };
      }
      speechSynthesis.speak(utterance);
    }

    play(0);
  }

  function chooseVoice() {
    openModal('音色', function (body, foot, close) {
      if (!window.speechSynthesis) {
        body.appendChild(el('div', 'desk-hint', '当前浏览器不支持语音朗读，请换用 Chrome / Edge / Safari。'));
        foot.appendChild(button('关闭', '', close));
        return;
      }
      var active = activeVoiceIndex();
      var defaultRow = el('div', 'desk-row' + (active < 0 ? ' active' : ''));
      defaultRow.appendChild(el('div', 'desk-row-main',
        '系统默认音色' + (active < 0 ? '（使用中）' : '')));
      defaultRow.addEventListener('click', function () {
        setActiveVoiceIndex(-1);
        close();
        chooseVoice();
      });
      body.appendChild(defaultRow);

      body.appendChild(el('div', 'desk-label', '已保存的音色（最多 ' + MAX_PRESETS + ' 个）'));
      var presets = loadPresets();
      if (!presets.length) {
        body.appendChild(el('div', 'desk-hint', '还没有自定义音色，点下方「添加新音色」创建一个。'));
      }
      presets.forEach(function (preset, index) {
        var rowEl = el('div', 'desk-row' + (index === active ? ' active' : ''));
        var main = el('div', 'desk-row-main',
          preset.name + (index === active ? '（使用中）' : '')
          + '\n音调 ' + fmt1(preset.pitch) + ' · 语速 ' + fmt1(preset.rate)
          + ' · ' + (preset.voice || '系统默认'));
        main.addEventListener('click', function () {
          setActiveVoiceIndex(index);
          close();
          chooseVoice();
        });
        rowEl.append(main, button('删除', 'danger', function (event) {
          event.stopPropagation();
          var list = loadPresets();
          list.splice(index, 1);
          savePresets(list);
          var current = activeVoiceIndex();
          if (current === index) {
            setActiveVoiceIndex(-1);
          } else if (current > index) {
            setActiveVoiceIndex(current - 1);
          }
          close();
          chooseVoice();
        }));
        body.appendChild(rowEl);
      });

      var full = presets.length >= MAX_PRESETS;
      var add = button(full ? '已达上限 ' + MAX_PRESETS + ' 个' : '＋ 添加新音色', 'primary', function () {
        if (loadPresets().length >= MAX_PRESETS) return;
        showVoiceEditor();
      });
      add.disabled = full;
      foot.appendChild(add);
      foot.appendChild(button('关闭', '', close));
    });
  }

  function showVoiceEditor() {
    openModal('添加音色', function (body, foot, close) {
      body.appendChild(el('div', 'desk-label', '音色'));
      var select = document.createElement('select');
      select.className = 'desk-input';
      var defaultOption = document.createElement('option');
      defaultOption.value = '';
      defaultOption.textContent = '系统默认';
      select.appendChild(defaultOption);
      zhVoices().forEach(function (voice) {
        var option = document.createElement('option');
        option.value = voice.name;
        option.textContent = voice.name + '（' + voice.lang + '）';
        select.appendChild(option);
      });
      body.appendChild(select);

      var pitch = 1;
      var rate = 1;

      var pitchLabel = el('div', 'desk-label', '音调：' + fmt1(pitch));
      var pitchInput = document.createElement('input');
      pitchInput.type = 'range';
      pitchInput.min = '0.5';
      pitchInput.max = '2';
      pitchInput.step = '0.1';
      pitchInput.value = '1';
      pitchInput.style.width = '100%';
      pitchInput.addEventListener('input', function () {
        pitch = parseFloat(pitchInput.value);
        pitchLabel.textContent = '音调：' + fmt1(pitch);
      });
      body.append(pitchLabel, pitchInput);

      var rateLabel = el('div', 'desk-label', '语速：' + fmt1(rate));
      var rateInput = document.createElement('input');
      rateInput.type = 'range';
      rateInput.min = '0.5';
      rateInput.max = '2';
      rateInput.step = '0.1';
      rateInput.value = '1';
      rateInput.style.width = '100%';
      rateInput.addEventListener('input', function () {
        rate = parseFloat(rateInput.value);
        rateLabel.textContent = '语速：' + fmt1(rate);
      });
      body.append(rateLabel, rateInput);

      body.appendChild(el('div', 'desk-hint', '提示：点「试听」会随机读 15 个字，方便对比音色。'));

      foot.appendChild(button('取消', '', close));
      foot.appendChild(button('试听', '', function () {
        readAloud(randomSample(), { voice: select.value, pitch: pitch, rate: rate });
      }));
      foot.appendChild(button('保存', 'primary', function () {
        var list = loadPresets();
        if (list.length >= MAX_PRESETS) {
          alert('最多保存 ' + MAX_PRESETS + ' 个音色，请先删除一个');
          return;
        }
        list.push({
          name: '音色 ' + (list.length + 1),
          voice: select.value,
          pitch: pitch,
          rate: rate
        });
        savePresets(list);
        setActiveVoiceIndex(list.length - 1);
        close();
        readAloud(randomSample());
        chooseVoice();
      }));
    });
  }

  // ---------------- API Key 管理 ----------------

  function resetKey() {
    openModal('更换 Key', function (body, foot, close) {
      var listBox = el('div');
      body.appendChild(listBox);
      var balance = el('div', 'desk-hint', '余额：查询中…');
      body.appendChild(balance);

      function send(payload) {
        fetch('/api/keys/', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        }).then(function (response) {
          return response.json().catch(function () { return {}; });
        }).then(function (data) {
          if (data && data.error) alert(data.error);
          load();
        }).catch(function () {
          alert('操作失败，请检查网络后重试');
        });
      }

      function load() {
        fetch('/api/keys/').then(function (response) {
          return response.json();
        }).then(function (data) {
          listBox.innerHTML = '';
          var keys = (data && data.keys) || [];
          if (!keys.length) {
            listBox.appendChild(el('div', 'desk-hint', '还没有保存 Key，请在下方添加。'));
          }
          keys.forEach(function (item, index) {
            var rowEl = el('div', 'desk-row' + (item.active ? ' active' : ''));
            var main = el('div', 'desk-row-main',
              item.masked + (item.active ? '（使用中）' : ''));
            main.addEventListener('click', function () {
              if (!item.active) send({ action: 'use', index: index });
            });
            rowEl.append(main, button('删除', 'danger', function (event) {
              event.stopPropagation();
              if (confirm('确定删除这个 Key 吗？')) send({ action: 'delete', index: index });
            }));
            listBox.appendChild(rowEl);
          });
          fetch('/api/keys/balance/').then(function (response) {
            return response.json();
          }).then(function (info) {
            balance.textContent = '余额：' + (info.balance || info.error || '未知');
          }).catch(function () {
            balance.textContent = '余额：网络错误';
          });
        }).catch(function () {
          balance.textContent = '余额：读取失败';
        });
      }

      body.appendChild(el('div', 'desk-label', '添加新 Key（最多 3 个）'));
      var input = document.createElement('input');
      input.type = 'password';
      input.className = 'desk-input';
      input.placeholder = '粘贴 DeepSeek API Key（sk-...）';
      body.appendChild(input);

      foot.appendChild(button('添加', 'primary', function () {
        var value = input.value.trim();
        if (!value) {
          input.focus();
          return;
        }
        send({ action: 'add', api_key: value });
        input.value = '';
      }));
      foot.appendChild(button('关闭', '', close));
      load();
    });
  }

  // ---------------- 更新检查 ----------------

  var VERSION_URLS = [
    'https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/version.json',
    'https://ghproxy.net/https://raw.githubusercontent.com/2078357126-design/Ai-django-chat/main/version.json',
    'https://cdn.jsdelivr.net/gh/2078357126-design/Ai-django-chat@main/version.json'
  ];

  function fetchRemoteVersion(index) {
    if (index >= VERSION_URLS.length) return Promise.reject(new Error('全部线路失败'));
    return fetch(VERSION_URLS[index], { cache: 'no-store' })
      .then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      })
      .catch(function () {
        return fetchRemoteVersion(index + 1);
      });
  }

  function checkUpdate() {
    fetch('/api/meta/').then(function (response) {
      return response.json();
    }).then(function (info) {
      var current = (info && info.version) || '3.0';
      fetchRemoteVersion(0).then(function (remote) {
        if (remote.versionName && remote.versionName !== current) {
          var notes = remote.notes ? '\n\n' + remote.notes : '';
          if (confirm('发现新版本 ' + remote.versionName
            + '（当前 ' + current + '）' + notes + '\n\n是否前往下载？')) {
            window.open('https://github.com/2078357126-design/Ai-django-chat/releases/latest', '_blank');
          }
        } else {
          alert('已是最新版本 ' + current);
        }
      }).catch(function () {
        alert('检查更新失败，请检查网络后重试');
      });
    }).catch(function () {
      alert('检查更新失败，请检查网络后重试');
    });
  }

  // ---------------- 聊天 ----------------

  var sessionId = 'desk-' + Math.random().toString(36).slice(2, 10);

  function visitorId() {
    var id = LS.getItem('desktop_visitor_id');
    if (!id) {
      id = 'v-' + Math.random().toString(36).slice(2, 10);
      LS.setItem('desktop_visitor_id', id);
    }
    return id;
  }

  function chat(text, callbackId) {
    var role = findRole(activeRoleId());
    var payload = { message: text, session_id: sessionId, visitor_id: visitorId() };
    if (role) {
      payload.role = { name: role.name, gender: role.gender, story: role.story };
    }
    fetch('/api/chat/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (data) {
        return { ok: response.ok, data: data };
      });
    }).then(function (result) {
      var data = result.data || {};
      if (!result.ok && !data.error) data.error = '请求失败';
      window.__onResult(callbackId, JSON.stringify(data));
    }).catch(function (error) {
      window.__onResult(callbackId,
        JSON.stringify({ error: '网络错误：' + error.message }));
    });
  }

  window.Android = {
    chat: chat,
    activeRole: activeRoleId,
    roleName: function () {
      var role = findRole(activeRoleId());
      return role ? role.name : '';
    },
    speak: function (text) { readAloud(text); },
    stopSpeak: function () {
      if (window.speechSynthesis) speechSynthesis.cancel();
    },
    showRoles: showRoles,
    chooseVoice: chooseVoice,
    resetKey: resetKey,
    checkUpdate: checkUpdate,
    confirmClearHistory: function () {
      if (confirm('确定要删除全部聊天记录吗？')) {
        if (window.__clearAll) window.__clearAll();
      }
    },
    confirmDeleteTurn: function (index) {
      if (confirm('确定删除这条聊天记录吗？')) {
        if (window.__deleteTurn) window.__deleteTurn(index);
      }
    },
    modelName: function () { return 'deepseek-flash'; }
  };
})();
