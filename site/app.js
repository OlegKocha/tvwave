const repo = 'https://github.com/OlegKocha/tvwave';
const icons = {
 home:'<path d="m3 10 9-7 9 7v10H3Z"/><path d="M9 20v-7h6v7"/>',
 screen:'<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
 grid:'<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 3v18M9 15h12"/>',
 play:'<path d="m9 5 11 7-11 7Z"/>',
 archive:'<path d="M3 11a9 9 0 1 1 2 7M3 4v7h7M12 7v6l4 2"/>',
 search:'<circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/>',
 phone:'<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M10 18h4M9 5h6"/>',
 backup:'<path d="M4 8h16v13H4ZM2 3h20v5H2ZM9 12h6"/>',
 download:'<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
 help:'<circle cx="12" cy="12" r="9"/><path d="M9 8a3 3 0 1 1 5 3c-2 1-2 2-2 3m0 3v.1"/>',
 settings:'<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="8" cy="18" r="2"/>',
 check:'<path d="m5 12 4 4L19 6"/>',
 arrow:'<path d="M5 12h14m-5-5 5 5-5 5"/>',
 info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.1"/>'
};
const icon = name => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] || icons.screen}</svg>`;
const content = {
 ru: {
 title:'TV Wave — ваши каналы на большом экране', subtitle:'Плеер для вашего телевизора', guide:'Обзор приложения', navSearch:'Найти раздел…', navEmpty:'Раздел не найден', navGroups:['Знакомство','Как пользоваться'], nav:['Обзор','Скриншоты','Возможности','Начало работы','Управление пультом','Резервная копия','Вопросы и ответы','Скачать приложение'], open:'Открытый исходный код', sideNote:'Свой плейлист. Свой телевизор.', menu:'Меню', platform:'ANDROID TV / GOOGLE TV', hero:'Ваши каналы.<br><span>На большом экране.</span>', lead:'TV Wave — IPTV-плеер с телегидом, архивом передач и управлением с пульта. Подключите свой плейлист и выбирайте, что смотреть.', download:'Скачать APK', explore:'Посмотреть интерфейс', downloadNote:'Бесплатно · универсальный APK · без аккаунта TV Wave', tabs:['Телегид','Плеер','Первый запуск'], galleryHint:'Нажмите на экран, чтобы увеличить', captions:['Каналы, программа передач и живое видео — на одном экране.','Пауза, перемотка, субтитры и избранное под рукой.','Добавьте плейлист или восстановите настройки из копии.'], enlarge:'Увеличить скриншот', close:'Закрыть', notice:'TV Wave не содержит каналов и подписок. Для просмотра нужен ваш плейлист и право доступа к его содержимому.', featuresTitle:'Всё, что нужно для просмотра', featuresMeta:'Для управления нужен только пульт.', features:[['grid','Телегид рядом с эфиром','Читайте описание и листайте расписание, пока канал продолжает играть в маленьком окне.'],['archive','Вернуться к пропущенному','Открывайте запись, начинайте передачу сначала и возвращайтесь в эфир. Архив должен поддерживаться вашим провайдером.'],['search','Поиск каналов и передач','Ищите по названию, повторяйте недавние запросы и смотрите, доступна ли найденная передача в записи.'],['phone','Передача ссылок с телефона','Отсканируйте QR-код на телевизоре и отправьте адрес плейлиста или EPG. Телефон и ТВ должны быть в одной сети.'],['settings','Интерфейс под вас','Настройте масштаб, выберите язык, добавьте любимые каналы в избранное. Все действия доступны с пульта.'],['grid','Свои источники EPG','Подключите несколько телепрограмм, задайте приоритет и соответствия каналов. Расписание обновляется в фоне.']], startTitle:'От установки до первого канала', startIntro:'Понадобятся Android TV или приставка, интернет и плейлист вашего IPTV-провайдера.', steps:[['Установите TV Wave','Скачайте APK, перенесите на телевизор и откройте файл для установки. При первом запуске выберите язык.'],['Добавьте плейлист','Вставьте ссылку M3U/M3U8, выберите файл или передайте адрес с телефона по QR-коду.'],['Подключите телепрограмму','Добавьте адрес XMLTV или XMLTV.GZ в настройках EPG. После загрузки расписание появится рядом с каналами.']], remoteTitle:'Уже знакомый вам пульт', remoteIntro:'Стрелки, OK и «Назад». Не нужно подключать мышь или разбираться в жестах.', remoteRows:[['↑ ↓','Выбрать канал или пункт меню'],['← →','Перейти между разделами и передачами'],['OK','Открыть выбранное или начать просмотр'],['Назад','Вернуться к предыдущему экрану']], backupEyebrow:'Резервная копия', backupTitle:'Настроили один раз.<br>Сохранили в копию.', backupText:'Перенесите настройки на другой телевизор или восстановите их после переустановки. Резервную копию можно открыть сразу при первом запуске.', backupNote:'Расписание передач загрузится заново. История поиска в копию не входит.', backupItems:['Плейлисты','Адреса телепрограмм','Избранное и настройки','Ручные соответствия EPG'], faqTitle:'Перед установкой', faqs:[['Здесь уже есть телеканалы?','Нет. TV Wave — плеер для ваших источников. Каналы, доступ к ним и архив предоставляет ваш IPTV-провайдер.'],['Почему архив доступен не у всех передач?','Наличие и срок хранения записей зависят от провайдера и канала. Даже если канал поддерживает архив, отдельная запись может быть недоступна на сервере.'],['Как обновлять приложение?','Есть два способа: скачать tvwave.apk из GitHub Releases и открыть его на телевизоре или перейти в «Настройки → Обновление TV Wave», проверить наличие новой версии и нажать «Обновить». Подтвердите установку в Android. При обновлении плейлисты и настройки сохраняются.'],['Можно ли добавить другой язык?','Да. Переводы хранятся в отдельных JSON-файлах. Скопируйте английский перевод, переведите строки и предложите изменение в репозитории на GitHub.']], downloadTitle:'Попробуйте на своём ТВ', downloadText:'Скачайте последнюю версию из GitHub Releases. Один APK для телевизоров и приставок на Android.', releases:'Все версии', footer:'TV Wave · IPTV-плеер с открытым кодом', issues:'Сообщить об ошибке', skip:'К содержимому'
 },
 en: {
 title:'TV Wave — your channels on the big screen', subtitle:'A player for your television', guide:'App overview', navSearch:'Find a section…', navEmpty:'No sections found', navGroups:['Discover','Get started'], nav:['Overview','Screenshots','Features','Getting started','Remote control','Backup','Questions & answers','Download'], open:'Open source', sideNote:'Your playlist. Your television.', menu:'Menu', platform:'ANDROID TV / GOOGLE TV', hero:'Your channels.<br><span>On the big screen.</span>', lead:'TV Wave is an IPTV player with a programme guide, catch-up and remote control navigation. Add your playlist and choose what to watch.', download:'Download APK', explore:'Explore the interface', downloadNote:'Free · universal APK · no TV Wave account', tabs:['TV guide','Player','First launch'], galleryHint:'Click the screen to enlarge', captions:['Channels, programme listings and live video, together.','Pause, seek, subtitles and favourites within reach.','Add a playlist or restore your settings from a backup.'], enlarge:'Enlarge screenshot', close:'Close', notice:'TV Wave does not include channels or subscriptions. You need your own playlist and permission to access its content.', featuresTitle:'Made for watching TV', featuresMeta:'All you need is your remote.', features:[['grid','Browse while you watch','Read descriptions and explore the schedule while your channel keeps playing in a small preview.'],['archive','Catch up on missed shows','Play a recording, restart a programme or return to live TV. Catch-up requires support from your provider.'],['search','Search channels and programmes','Search by name, repeat recent searches and see whether a programme is available as a recording.'],['phone','Send links from your phone','Scan the QR code on your TV and send a playlist or EPG URL. Both devices need to be on the same network.'],['settings','Make it comfortable','Adjust the interface scale, choose a language and save favourite channels. Navigate everything with your remote.'],['grid','Bring your own EPG','Add multiple programme guides, set their priority and match channels. Listings update in the background.']], startTitle:'From installation to your first channel', startIntro:'You need an Android TV or TV box, an internet connection and a playlist from your IPTV provider.', steps:[['Install TV Wave','Download the APK, transfer it to your TV and open it to install. Choose your language on first launch.'],['Add your playlist','Paste an M3U/M3U8 URL, choose a file or send the address from your phone using a QR code.'],['Connect a programme guide','Add an XMLTV or XMLTV.GZ URL in the EPG settings. Once loaded, listings appear next to your channels.']], remoteTitle:'The remote you already know', remoteIntro:'Arrow keys, OK and Back. No mouse to connect and no gestures to learn.', remoteRows:[['↑ ↓','Choose a channel or menu item'],['← →','Move between sections and programmes'],['OK','Open a selection or start watching'],['Back','Return to the previous screen']], backupEyebrow:'Backup & restore', backupTitle:'Set it up once.<br>Keep a backup.', backupText:'Move your settings to another TV or restore them after reinstalling. You can open a backup directly on first launch.', backupNote:'Programme listings are downloaded again. Search history is not included in backups.', backupItems:['Playlists','Programme guide URLs','Favourites and settings','Manual EPG matches'], faqTitle:'Before you install', faqs:[['Are TV channels included?','No. TV Wave is a player for sources you add yourself. Your IPTV provider supplies the channels, access and catch-up service.'],['Why are some recordings unavailable?','Availability and retention depend on your provider and the channel. Even when a channel supports catch-up, a particular recording may be unavailable on the server.'],['How do I update the app?','Download tvwave.apk from GitHub Releases and open it on your TV, or go to Settings → TV Wave updates, check for a new version and choose Update. Confirm installation in Android. Your playlists and settings are preserved when updating.'],['Can I contribute a translation?','Yes. Translations are stored in individual JSON files. Copy the English translation, translate the strings and propose your changes on GitHub.']], downloadTitle:'Try it on your TV', downloadText:'Get the latest version from GitHub Releases. One APK for Android televisions and TV boxes.', releases:'All releases', footer:'TV Wave · an open-source IPTV player', issues:'Report an issue', skip:'Skip to content'
 }
};
const allShots = ['guide','player','setup','search','programme','settings','appearance','epg','backup','update'];
const shots = allShots.filter(id => !['setup','appearance','backup'].includes(id));
Object.assign(content.ru, {
 tabs:['Телегид','Плеер','Первый запуск','Поиск','О передаче','Настройки','Оформление','Телепрограммы','Бекап','Обновление'],
 captions:['Каналы, расписание и видео — на одном экране.','Управление просмотром и информация о потоке.','Подключение своего плейлиста или восстановление из копии.','Поиск по каналам и названиям передач.','Описание выбранной передачи и расписание канала.','Все настройки собраны в боковом меню.','Масштаб интерфейса и выбор языка.','Источники расписания и автоматическое обновление.','Сохранение настроек и восстановление из файла.','Новая версия и список изменений из GitHub.'],
 previous:'Предыдущий скриншот',next:'Следующий скриншот',howTitle:'Как пользоваться TV Wave',howIntro:'Короткие маршруты по меню. Откройте картинку, чтобы рассмотреть детали.',
 tutorials:[
 ['setup','Подключить свой плейлист','Первый запуск → Добавить плейлист',['Выберите «Ссылка» или «Файл». Поддерживаются M3U и M3U8.','Вставьте адрес или выберите файл, затем нажмите «Добавить».','Для передачи ссылки с телефона выберите «С телефона по QR». Оба устройства должны быть в одной сети.']],
 ['epg','Добавить программу передач','Настройки → Телепрограммы → Добавить',['Вставьте адрес XMLTV или XMLTV.GZ и сохраните источник.','Дождитесь загрузки и обработки расписания.','Если программа не нашлась, откройте «Соответствия каналов» и выберите соответствие вручную. Интервал обновления задаётся на этом же экране.']],
 ['guide','Посмотреть, что идёт сейчас и дальше','Телегид → канал → вправо',['Выбирайте канал стрелками вверх и вниз. OK включает просмотр.','Нажмите вправо, чтобы перейти к передачам. Влево и вправо можно листать расписание.','OK на передаче открывает её описание. Видео в миниатюре продолжает играть.']],
 ['programme','Открыть пропущенную передачу','Телегид → передача → OK → OK',['Откройте описание нужной передачи и выберите её в нижней ленте.','Нажмите OK для запуска записи. Для текущей передачи можно выбрать просмотр сначала или прямой эфир.','Доступность записи и глубина архива зависят от провайдера. В плеере можно перематывать запись и вернуться в эфир.']],
 ['search','Найти канал или передачу','Меню слева → Поиск',['Введите название и подтвердите ввод галочкой на клавиатуре.','Сначала показываются каналы, ниже — найденные передачи. Информация о выбранном результате находится справа.','Короткое OK на недавнем запросе повторяет поиск. Удержание OK открывает подтверждение удаления запроса.']],
 ['appearance','Сделать интерфейс удобнее','Настройки → Оформление',['Выберите ползунок «Масштаб интерфейса».','Нажимайте влево для уменьшения и вправо для увеличения. Изменения видны сразу и сохраняются автоматически.','Ниже можно изменить язык интерфейса.']],
 ['backup','Сохранить настройки и восстановить их','Настройки → Резервная копия',['Нажмите «Создать копию» и сохраните файл. Перенесите его на другое устройство перед удалением приложения.','На новом устройстве выберите «Восстановить» при первом запуске или «Восстановить из файла» в настройках.','Выберите файл, дождитесь проверки и подтвердите восстановление. Расписание загрузится заново, история поиска не переносится.']],
 ['update','Обновить приложение','Настройки → Обновление TV Wave',['Нажмите «Проверить обновление». Если доступна новая версия, появятся её номер и список изменений.','Нажмите «Обновить», дождитесь загрузки и подтвердите установку в системном окне Android. Плейлисты и настройки сохранятся.','Можно обновить и вручную: скачайте tvwave.apk из GitHub Releases, перенесите на телевизор и откройте файл поверх установленной версии. Удалять приложение не нужно.']]
 ]
});
Object.assign(content.en, {
 tabs:['TV guide','Player','First launch','Search','Programme details','Settings','Appearance','Programme guides','Backup','Updates'],
 captions:['Channels, listings and video on one screen.','Playback controls and stream information.','Add your own playlist or restore a backup.','Search channel names and programme titles.','Programme description and the channel schedule.','All settings in a side panel.','Interface scale and language selection.','Programme sources and automatic updates.','Save your settings or restore them from a file.','An available update and release notes from GitHub.'],
 previous:'Previous screenshot',next:'Next screenshot',howTitle:'Using TV Wave',howIntro:'Short routes through the menus. Open an image to see the details.',
 tutorials:[
 ['setup','Connect your playlist','First launch → Add playlist',['Choose URL or File. M3U and M3U8 are supported.','Paste a URL or select a file, then choose Add.','To send a URL from your phone, choose the QR option. Both devices must be on the same network.']],
 ['epg','Add a programme guide','Settings → Programme guides → Add',['Paste an XMLTV or XMLTV.GZ URL and save the source.','Wait for the guide to download and finish processing.','If a channel has no listings, use Channel matching to assign it manually. You can also set the update interval here.']],
 ['guide','See what is on now and next','TV guide → channel → Right',['Use Up and Down to select a channel. Press OK to watch.','Press Right to move to programmes. Use Left and Right to browse the schedule.','Press OK on a programme to read its description. Video keeps playing in the preview.']],
 ['programme','Watch a missed programme','TV guide → programme → OK → OK',['Open a programme’s details and select it in the bottom strip.','Press OK to play the recording. For a programme currently on air, choose to restart it or watch live.','Recording availability and retention depend on your provider. The player lets you seek through a recording and return to live TV.']],
 ['search','Find channels and programmes','Left menu → Search',['Enter a name and confirm using the keyboard’s check mark.','Channels appear first, followed by matching programmes. Details for the selected result appear on the right.','Press OK on a recent query to search again. Hold OK to open its deletion confirmation.']],
 ['appearance','Adjust the interface','Settings → Appearance',['Select the Interface scale slider.','Press Left to make it smaller or Right to make it larger. Changes appear immediately and save automatically.','Use the language option below to change the interface language.']],
 ['backup','Save and restore your settings','Settings → Backup',['Choose Create backup and save the file. Move it to another device before uninstalling the app.','On a new device, choose Restore on first launch, or Restore from file in Settings.','Select the file, wait for validation and confirm restoration. Listings download again; search history is not transferred.']],
 ['update','Update the app','Settings → TV Wave updates',['Choose Check for updates. If a new version is available, you will see its version number and release notes.','Choose Update, wait for the download and confirm installation in Android. Your playlists and settings will stay in place.','You can also update manually: download tvwave.apk from GitHub Releases, transfer it to your TV and open it to update the installed app. There is no need to uninstall it.']]
 ]
});
for (const t of Object.values(content)) {
 t.tabs = t.tabs.filter((_, i) => shots.includes(allShots[i]));
 t.captions = t.captions.filter((_, i) => shots.includes(allShots[i]));
}
const sectionIds = ['overview','screenshots','features','start','howto','remote','backup','faq','download'];
const sectionIcons = ['home','screen','grid','play','settings','settings','backup','help','download'];
let language = new URLSearchParams(location.search).get('lang') === 'en' ? 'en' : 'ru';
let screen = 0;
let pinnedSection = null;
let scrollFrame = 0;
const app = document.getElementById('app');
function navLabel(id){const oldIds=['overview','screenshots','features','start','remote','backup','faq','download'];return id==='howto'?(language==='ru'?'Возможности':'Features'):id==='features'?(language==='ru'?'Краткий обзор':'At a glance'):content[language].nav[oldIds.indexOf(id)];}
function render(){
 const t=content[language];
 document.documentElement.lang=language;
 document.title=t.title;
 document.querySelector('meta[name=description]').content=t.lead;
 app.innerHTML=`<a class="skip-link" href="#main">${t.skip}</a>
 <aside class="sidebar" id="navigation"><a class="brand" href="#overview"><img src="assets/mark.svg" alt="">TV Wave</a><div class="brand-note">${t.subtitle}</div>
 <label class="search-nav">${icon('search')}<input type="search" id="nav-filter" aria-label="${t.navSearch}" placeholder="${t.navSearch}"></label>
 <nav aria-label="${t.menu}">${sectionIds.map((id,i)=>`${i===0||i===3?`<div class="nav-label">${t.navGroups[i===0?0:1]}</div>`:''}<a class="nav-link ${i===0?'active':''}" href="#${id}">${icon(sectionIcons[i])}<span>${navLabel(id)}</span></a>`).join('')}</nav>
 <p class="nav-empty" hidden>${t.navEmpty}</p><div class="side-footer"><a href="${repo}" target="_blank" rel="noopener">${t.open} ↗</a>${t.sideNote}</div></aside>
 <button class="menu-backdrop" tabindex="-1" aria-label="${t.close}" hidden></button>
 <header class="topbar"><button class="mobile-menu" aria-expanded="false" aria-controls="navigation"><img src="assets/mark.svg" alt="">TV Wave <span>☰</span></button><div class="crumb">TV Wave</div><div class="top-actions"><a href="${repo}" target="_blank" rel="noopener">GitHub ↗</a><div class="languages" role="group" aria-label="Language"><button data-lang="ru" aria-pressed="${language==='ru'}">RU</button><button data-lang="en" aria-pressed="${language==='en'}">EN</button></div></div></header>
 <main class="main" id="main"><div class="content"><section class="hero" id="overview"><div class="hero-top"><span class="status-dot"></span>${t.platform}</div><h1>${t.hero}</h1><p class="lead">${t.lead}</p><div class="notice">${icon('info')}<span>${t.notice}</span></div><div class="actions"><a class="button primary" href="${repo}/releases/latest/download/tvwave.apk">${icon('download')}${t.download}</a></div></section>
 <section class="gallery" id="screenshots" aria-label="${t.nav[1]}"><div class="gallery-top"><div class="tabs" role="tablist" aria-label="${t.nav[1]}">${t.tabs.map((tab,i)=>`<button role="tab" id="tab-${i}" aria-controls="screen-panel" aria-selected="${i===screen}" tabindex="${i===screen?0:-1}" data-screen="${i}">${tab}</button>`).join('')}</div></div><div class="gallery-controls"><button class="gallery-arrow" data-step="-1" aria-label="${t.previous}">←</button><span id="gallery-count" aria-live="polite">${screen+1} / ${shots.length}</span><button class="gallery-arrow" data-step="1" aria-label="${t.next}">→</button></div><div id="screen-panel" role="tabpanel" aria-labelledby="tab-${screen}"><button class="screen-button" aria-label="${t.enlarge}"><img id="screen" src="assets/${shots[screen]}-${language}.png?v=images-20261110-language-selection" alt="${t.tabs[screen]} TV Wave" width="1920" height="1080"><span class="zoom-mark" aria-hidden="true">⤢</span></button><div class="gallery-caption"><span>TV Wave / ${t.tabs[screen]}</span></div></div></section>
 <section class="section" id="features"><div class="section-head"><h2>${t.featuresTitle}</h2><span class="mono">${t.featuresMeta}</span></div><div class="feature-list">${t.features.map(f=>`<article class="feature">${icon(f[0])}<div><h3>${f[1]}</h3><p>${f[2]}</p></div></article>`).join('')}</div></section>
 <section class="section" id="start"><h2>${t.startTitle}</h2><div class="steps">${t.steps.map((s,i)=>`<article class="step"><div class="step-num">${i+1}</div><h3>${s[0]}</h3><p>${s[1]}</p></article>`).join('')}</div></section>
 <section class="section" id="howto"><h2>${t.howTitle}</h2><div class="tutorials">${t.tutorials.map((item,i)=>`<article class="tutorial"><div class="tutorial-copy"><span class="eyebrow">${String(i+1).padStart(2,'0')}</span><h3>${item[1]}</h3><p class="menu-path">${item[2]}</p><ol>${item[3].map(step=>`<li>${step}</li>`).join('')}</ol></div><button class="tutorial-image" data-image="${item[0]}" aria-label="${t.enlarge}: ${item[1]}"><img src="assets/${item[0]}-${language}.png?v=images-20261110-language-selection" alt="${item[1]}" width="1920" height="1080" loading="lazy"><span>${t.enlarge} ↗</span></button></article>`).join('')}</div></section>
 <section class="section" id="remote"><h2>${t.remoteTitle}</h2><p class="intro">${t.remoteIntro}</p><div class="remote-block"><div class="remote" aria-hidden="true"><div class="dpad"><span></span><span>↑</span><span></span><span>←</span><b>OK</b><span>→</span><span></span><span>↓</span></div><div class="remote-back"><span aria-hidden="true">←</span> ${t.remoteRows[3][0]}</div></div><div class="remote-lines">${t.remoteRows.map(r=>`<div><kbd>${r[0]}</kbd><span>${r[1]}</span></div>`).join('')}</div></div></section>
 <section class="section backup" id="backup"><div class="backup-copy"><div class="eyebrow">${t.backupEyebrow}</div><h2>${t.backupTitle}</h2><p>${t.backupText}</p><p>${t.backupNote}</p></div><ul class="backup-list">${t.backupItems.map(s=>`<li>${s}${icon('check')}</li>`).join('')}</ul></section>
 <section class="section" id="faq"><div class="section-head"><h2>${t.faqTitle}</h2></div>${t.faqs.map(f=>`<details><summary>${f[0]}</summary><p>${f[1]}</p></details>`).join('')}</section>
 <section class="section" id="download"><div class="download-panel"><div><h2>${t.downloadTitle}</h2><p>${t.downloadText}</p></div><a class="button primary" href="${repo}/releases/latest/download/tvwave.apk">${icon('download')}${t.download}</a></div></section>
 <footer class="footer"><span>${t.footer}</span><div class="footer-links"><a href="${repo}/releases" target="_blank" rel="noopener">${t.releases} ↗</a><a href="${repo}/issues" target="_blank" rel="noopener">${t.issues} ↗</a></div></footer></div></main>
 <dialog aria-label="${t.nav[1]}"><header><span id="dialog-title"></span><button id="close-dialog">${t.close} ×</button></header><img id="dialog-image" alt=""><div class="dialog-controls"><button data-step="-1" aria-label="${t.previous}">←</button><span id="dialog-count"></span><button data-step="1" aria-label="${t.next}">→</button></div></dialog>`;
 bind();
}
function selectScreen(index, focus=false){
 screen=(index+shots.length)%shots.length;const t=content[language];
 document.querySelectorAll('[data-screen]').forEach((b,i)=>{b.setAttribute('aria-selected',String(i===screen));b.tabIndex=i===screen?0:-1;if(focus&&i===screen)b.focus();});
 document.getElementById('screen').src=`assets/${shots[screen]}-${language}.png?v=images-20261110-language-selection`;
 document.getElementById('screen').alt=`${t.tabs[screen]} TV Wave`;
 document.getElementById('screen-panel').setAttribute('aria-labelledby',`tab-${screen}`);
 document.querySelector('.gallery-caption span:last-child').textContent=`TV Wave / ${t.tabs[screen]}`;
 document.getElementById('gallery-count').textContent=`${screen+1} / ${shots.length}`;
 syncDialog();
}
function syncDialog(){
 document.querySelector('.dialog-controls').hidden=false;
 document.getElementById('dialog-image').src=`assets/${shots[screen]}-${language}.png?v=images-20261110-language-selection`;
 document.getElementById('dialog-image').alt=content[language].tabs[screen];
 document.getElementById('dialog-title').textContent=content[language].tabs[screen];
 document.getElementById('dialog-count').textContent=`${screen+1} / ${shots.length}`;

}
function bind(){
 document.querySelectorAll('[data-lang]').forEach(b=>b.addEventListener('click',()=>{language=b.dataset.lang;try{const u=new URL(location.href);u.searchParams.set('lang',language);history.replaceState(null,'',u);}catch{}render();}));
 document.querySelectorAll('[data-screen]').forEach(b=>{b.addEventListener('click',()=>selectScreen(Number(b.dataset.screen)));b.addEventListener('keydown',e=>{let n=screen;if(e.key==='ArrowRight')n=(screen+1)%shots.length;else if(e.key==='ArrowLeft')n=(screen+shots.length-1)%shots.length;else if(e.key==='Home')n=0;else if(e.key==='End')n=shots.length-1;else return;e.preventDefault();selectScreen(n,true);});});
 const dialog=document.querySelector('dialog');
 document.querySelector('.screen-button').addEventListener('click',()=>{syncDialog();dialog.showModal();});
 document.querySelectorAll('[data-step]').forEach(b=>b.addEventListener('click',()=>selectScreen(screen+Number(b.dataset.step))));
 document.querySelectorAll('[data-image]').forEach(b=>b.addEventListener('click',()=>{
 const index=shots.indexOf(b.dataset.image);
 if(index>=0){selectScreen(index);}else{
  const img=b.querySelector('img');
  document.getElementById('dialog-image').src=img.src;
  document.getElementById('dialog-image').alt=img.alt;
  document.getElementById('dialog-title').textContent=img.alt;
  document.querySelector('.dialog-controls').hidden=true;
 }
 dialog.showModal();
 }));
 dialog.addEventListener('keydown',e=>{if(!document.querySelector('.dialog-controls').hidden&&(e.key==='ArrowLeft'||e.key==='ArrowRight')){e.preventDefault();selectScreen(screen+(e.key==='ArrowRight'?1:-1));}});
 document.getElementById('close-dialog').addEventListener('click',()=>dialog.close());
 dialog.addEventListener('click',e=>{if(e.target===dialog)dialog.close();});
 const menu=document.querySelector('.mobile-menu');const side=document.querySelector('.sidebar');
 const closeMenu=()=>setMenuOpen(false);
 menu.addEventListener('click',()=>setMenuOpen(!side.classList.contains('open')));
 document.querySelector('.menu-backdrop').addEventListener('click',closeMenu);
 document.querySelectorAll('.sidebar a[href^="#"]').forEach(a=>a.addEventListener('click',closeMenu));
 document.querySelector('main').addEventListener('click',closeMenu);
 document.addEventListener('keydown',handleEscape);
 document.getElementById('nav-filter').addEventListener('input',e=>{const q=e.target.value.toLocaleLowerCase().trim();let found=0;document.querySelectorAll('.nav-link').forEach(a=>{a.hidden=!a.textContent.toLocaleLowerCase().includes(q);if(!a.hidden)found++;});document.querySelector('.nav-empty').hidden=found>0;document.querySelectorAll('.nav-label').forEach(x=>x.hidden=!!q);});
 document.querySelectorAll('a[href^="#"]').forEach(a=>a.addEventListener('click',e=>{
 const target=document.getElementById(a.hash.slice(1));if(!target||!sectionIds.includes(target.id))return;
 e.preventDefault();pinnedSection=target.id;setActive(target.id);history.replaceState(null,'',a.hash);target.scrollIntoView({behavior:window.matchMedia('(prefers-reduced-motion:reduce)').matches?'instant':'smooth',block:'start'});
 }));
 pinnedSection=sectionIds.includes(location.hash.slice(1))?location.hash.slice(1):null;
 updateNavigation();
}
function setActive(id){document.querySelectorAll('.nav-link').forEach(a=>{const active=a.hash==='#'+id;a.classList.toggle('active',active);if(active)a.setAttribute('aria-current','location');else a.removeAttribute('aria-current');});}
function updateNavigation(){
 if(pinnedSection){setActive(pinnedSection);return;}
 const edge=document.querySelector('.topbar').getBoundingClientRect().bottom+40;
 let current=sectionIds[0];for(const id of sectionIds){if(document.getElementById(id).getBoundingClientRect().top<=edge)current=id;}
 setActive(current);
}
function releaseNavigation(){pinnedSection=null;requestAnimationFrame(updateNavigation);}
window.addEventListener('scroll',()=>{cancelAnimationFrame(scrollFrame);scrollFrame=requestAnimationFrame(updateNavigation);},{passive:true});
window.addEventListener('wheel',releaseNavigation,{passive:true});
window.addEventListener('touchmove',releaseNavigation,{passive:true});
window.addEventListener('keydown',e=>{if(['PageDown','PageUp','Home','End','ArrowDown','ArrowUp',' '].includes(e.key)&&!e.target.closest('input,button,dialog'))releaseNavigation();});

function setMenuOpen(open){
 document.querySelector('.sidebar').classList.toggle('open',open);
 document.querySelector('.mobile-menu').setAttribute('aria-expanded',String(open));
 document.querySelector('.menu-backdrop').hidden=!open;
 document.querySelector('main').inert=open;
}
function handleEscape(e){if(e.key==='Escape'&&document.querySelector('.sidebar.open')){setMenuOpen(false);document.querySelector('.mobile-menu').focus();}}
window.matchMedia('(max-width:760px)').addEventListener('change',e=>{if(!e.matches)setMenuOpen(false);});
render();
