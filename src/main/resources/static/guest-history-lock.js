(() => {
  'use strict';
  const API_ME='/api/auth/me';
  const RETURN_KEY='stremng.auth.returnTo';
  const GOOGLE_URL='/oauth2/authorization/google';
  const LOGIN_PENDING_KEY='stremng.auth.loginPending';
  let lastFocus=null;
  // A pending-login flag is only meaningful on the ?auth=login redirect after a failed attempt.
  try{if(new URLSearchParams(location.search).get('auth')!=='login')sessionStorage.removeItem(LOGIN_PENDING_KEY)}catch(_){}
  const routeLocks={
    'index.html':[
      'section[aria-labelledby="trendsTitle"] .charts > .panel',
      'section[aria-labelledby="matchesTitle"] .match-grid > .panel'
    ],
    'game.html':[
      '.grid > .panel',
      '.peak-section .peak-card',
      '#analyticsPanel > .panel'
    ],
    'channel.html':[
      '.comparison-panel',
      '.stats .stat:not(:first-child)',
      'main > .grid > .panel'
    ],
    'match.html':[
      'main.page > .grid > .panel',
      'main.page > section.panel[style*="margin-top"]'
    ],
    'stream-comparison.html':['#candidatePicker','#chartWrap','#dataTableWrap']
  };
  const SAMPLE_LINE='M0 86 C40 70 65 75 95 58 S150 42 180 53 S230 30 270 38 S330 15 380 28';
  function addStyles(){
    if(document.getElementById('guest-history-lock-styles'))return;
    const s=document.createElement('style');s.id='guest-history-lock-styles';
    s.textContent=`
      .guest-history-locked{position:relative!important;overflow:hidden!important;isolation:isolate!important;min-height:90px}
      .guest-history-locked>*:not(.guest-history-lock-overlay):not(.guest-demo-background){filter:blur(5px)!important;user-select:none!important;pointer-events:none!important}
      .guest-demo-background{position:absolute;inset:0;z-index:0;pointer-events:none;opacity:.86;filter:blur(2.8px);padding:38px 18px 14px;box-sizing:border-box}
      .guest-demo-background svg{width:100%;height:100%;display:block;overflow:visible}
      .guest-demo-background .demo-grid{stroke:#263747;stroke-width:1;opacity:.85}
      .guest-demo-background .demo-line{fill:none;stroke:#56b4ef;stroke-width:3;stroke-linecap:round}
      .guest-demo-background .demo-area{fill:url(#guestDemoGradient);opacity:.28}
      .guest-demo-background .demo-bars{fill:#56b4ef;opacity:.78}
      .guest-history-lock-overlay{position:absolute!important;inset:0!important;z-index:20!important;display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;gap:9px!important;padding:16px!important;text-align:center!important;background:rgba(8,12,18,.66)!important;color:#f4f7fb!important;border:1px solid rgba(145,165,190,.18)!important;border-radius:inherit!important;box-sizing:border-box!important;backdrop-filter:blur(1px)!important}
      .guest-history-lock-icon{display:grid;place-items:center;width:54px;height:54px;flex:0 0 auto;border-radius:50%;background:rgba(112,145,180,.28);border:1px solid rgba(190,214,238,.22);color:#f4f8ff;font-size:27px;line-height:1;box-shadow:0 8px 24px rgba(0,0,0,.18)}
      .guest-history-lock-overlay strong{font-size:15px;line-height:1.3}
      .guest-history-lock-overlay span{max-width:440px;color:#c0ccda;font-size:12px;line-height:1.45}
      .guest-history-lock-actions{display:flex;flex-wrap:wrap;justify-content:center;gap:7px}
      .guest-history-lock-overlay a{display:inline-flex;align-items:center;justify-content:center;text-decoration:none;border:1px solid #344456;border-radius:8px;padding:7px 10px;font-size:12px;color:#eef2f7;background:#182330;cursor:pointer;white-space:nowrap}
      .guest-history-lock-overlay a.primary{color:#071019;background:#e8eef5;border-color:#e8eef5;font-weight:700}
      .guest-history-locked.guest-lock-compact{min-height:118px!important}
      .guest-lock-compact .guest-demo-background{padding:18px 10px 8px}
      .guest-lock-compact .guest-history-lock-overlay{gap:5px!important;padding:8px!important}
      .guest-lock-compact .guest-history-lock-icon{width:35px;height:35px;font-size:18px}
      .guest-lock-compact .guest-history-lock-overlay strong{font-size:11px}
      .guest-lock-compact .guest-history-lock-overlay span{display:none}
      .guest-lock-compact .guest-history-lock-overlay a{font-size:10px;padding:5px 7px}
      .guest-metric-icon-lock{position:absolute;right:8px;top:8px;z-index:5;display:grid;place-items:center;width:26px;height:26px;border-radius:50%;background:rgba(112,145,180,.28);border:1px solid rgba(190,214,238,.3);font-size:15px;line-height:1;pointer-events:none}
      .match-metric.guest-metric-locked{position:relative;overflow:hidden}
      .match-metric.guest-metric-locked>*:not(.guest-metric-icon-lock){filter:blur(4px)!important;user-select:none!important}
      #stAuthRoot .st-modal{backdrop-filter:blur(8px)!important;background:rgba(3,7,11,.62)!important}
      .st-login-modal{position:fixed;inset:0;z-index:10050;display:none;align-items:center;justify-content:center;padding:20px;background:rgba(3,7,11,.62);backdrop-filter:blur(9px);-webkit-backdrop-filter:blur(9px);font:14px/1.45 Inter,system-ui,sans-serif;color:#eef2f7}
      .st-login-modal.open{display:flex}
      .st-login-card{width:min(420px,100%);padding:26px;background:#101821;border:1px solid #304050;border-radius:16px;box-shadow:0 24px 90px #000b;position:relative}
      .st-login-card h2{margin:0 32px 7px 0;font-size:24px}
      .st-login-card p{margin:0 0 18px;color:#aebaca}
      .st-provider-row{display:grid;gap:10px;margin:0 0 18px}
      .st-provider-more:not([hidden]){display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}
      .st-login-card .st-provider-wide{height:46px;gap:10px;font-weight:600;font-size:15px;background:#1b2636;border-color:#4a5d73}
      .st-login-card .st-provider-wide span{color:#eef2f7}
      .st-login-card .st-provider-more .st-provider-icon{flex-direction:column;gap:4px;height:auto;min-height:78px;padding:9px 4px;font-size:12px;font-weight:600;color:#c5d0dc}
      .st-login-card .st-provider-more .st-provider-icon:disabled{opacity:.78;cursor:not-allowed}
      .st-login-card .st-provider-more .st-soon{padding:1px 7px;border-radius:999px;background:rgba(167,139,250,.18);color:#d6c8ff;font-style:normal;font-size:9px;font-weight:700;letter-spacing:.06em;text-transform:uppercase}
      .st-login-card .st-provider-icon{height:48px;min-width:0;display:flex;align-items:center;justify-content:center;padding:0;border:1px solid #344456;border-radius:10px;background:#151f2b;transition:border-color .16s ease,background .16s ease,transform .16s ease}
      .st-login-card .st-provider-icon svg{width:23px;height:23px;display:block}
      .st-login-card .st-provider-icon:not(:disabled):hover{border-color:#9b7bff;background:#20263a;transform:translateY(-1px)}
      .st-login-card .st-provider-icon:focus-visible{outline:2px solid #a78bfa;outline-offset:2px}
      .st-login-card .st-provider-icon:disabled{opacity:.42;cursor:not-allowed}
      .st-login-divider{display:flex;align-items:center;gap:10px;margin:2px 0 12px;color:#a9b6c6;font-size:11px;letter-spacing:.09em;white-space:nowrap}
      .st-login-divider:before,.st-login-divider:after{content:"";height:1px;flex:1;background:#3d4f63}
      @media(max-width:420px){.st-login-card{padding:22px 18px}.st-provider-row{gap:8px}.st-login-card .st-provider-icon{height:46px}}
      .st-login-card label{display:block;margin:10px 0 5px;color:#c5d0dc;font-size:12px}
      .st-login-card input{display:block;width:100%;padding:12px;border:1px solid #4a5d73;border-radius:8px;background:#0b1118;color:#f4f7fb;font:inherit;box-sizing:border-box}
      .st-login-card input::placeholder{color:#7d8da1}
      .st-login-card input:focus{outline:none;border-color:#a78bfa;box-shadow:0 0 0 3px rgba(167,139,250,.25)}
      .st-pass{position:relative}
      .st-pass input{padding-right:70px}
      .st-login-card .st-pass-toggle{position:absolute;right:6px;top:50%;transform:translateY(-50%);padding:5px 9px;border:0;background:transparent;color:#c9b8ff;font-size:12px;font-weight:600}
      .st-login-card .st-pass-toggle:focus-visible{outline:2px solid #a78bfa;outline-offset:1px}
      .st-login-card button,.st-login-card a{font:inherit;text-decoration:none;cursor:pointer;border:1px solid #344456;border-radius:8px;padding:10px 13px;background:#182330;color:#eef2f7}
      .st-login-card button.primary,.st-login-card a.primary{background:#e8eef5;color:#101820;border-color:#e8eef5;font-weight:700}
      .st-login-actions{display:grid;margin-top:16px}
      .st-login-card .st-login-actions .primary{width:100%;padding:13px;font-size:15px}
      .st-login-card .st-login-actions .primary:disabled{opacity:.6;cursor:progress}
      .st-login-card .st-login-switch{margin:16px 0 0;text-align:center;color:#aebaca;font-size:13px}
      .st-login-card .st-login-switch .st-login-signup{padding:0 2px;border:0;background:transparent;color:#c9b8ff;font-weight:600;text-decoration:underline}
      .st-login-close{position:absolute;right:16px;top:14px}
      .st-login-error{margin-top:12px;padding:9px 11px;border:1px solid #6b3340;border-radius:8px;background:#2a141b;color:#ffb4b4;font-size:13px}
      .st-login-error:empty{display:none}
      @media(max-width:600px){.guest-history-lock-overlay{gap:7px!important;padding:10px!important}.guest-history-lock-icon{width:42px;height:42px;font-size:22px}.guest-history-lock-overlay strong{font-size:13px}.guest-history-lock-overlay span{font-size:11px}}
    
      /* Compact account controls: no double border, no username label, no page-content overlap from a large pill. */
      /* Reset legacy auth wrappers as well as the new controls to prevent nested/double borders. */
      #stAuthRoot{position:fixed!important;top:16px!important;right:20px!important;left:auto!important;z-index:10020!important;pointer-events:none!important;width:auto!important;height:auto!important;min-width:0!important;max-width:none!important;margin:0!important;padding:0!important;background:transparent!important;border:0!important;border-radius:0!important;box-shadow:none!important;overflow:visible!important}
      body>header,main>header,header{padding-right:150px!important;box-sizing:border-box}
      #stAuthRoot #stAuthBar,#stAuthRoot .st-auth-bar,#stAuthRoot .st-account-menu{pointer-events:auto!important;display:flex!important;align-items:center!important;justify-content:flex-end!important;gap:0!important;width:auto!important;height:auto!important;min-height:0!important;min-width:0!important;margin:0!important;padding:0!important;background:transparent!important;border:0!important;border-radius:0!important;box-shadow:none!important;overflow:visible!important}
      #stAuthRoot .st-login-top-button{display:inline-flex!important;align-items:center!important;justify-content:center!important;padding:8px 14px!important;border:1px solid #344456!important;border-radius:9px!important;background:#182330!important;color:#eef2f7!important;cursor:pointer;font:inherit;box-shadow:none!important}
      #stAuthRoot .st-account-avatar,#stAuthRoot img.st-account-avatar,#stAuthRoot span.st-account-avatar{width:38px!important;height:38px!important;min-width:38px!important;min-height:38px!important;max-width:38px!important;max-height:38px!important;flex:0 0 38px!important;aspect-ratio:1/1!important;box-sizing:border-box!important;border-radius:50%!important;object-fit:cover!important;object-position:center!important;background:#a746c5!important;display:grid!important;place-items:center!important;border:1px solid rgba(230,210,255,.65)!important;color:#fff!important;font-weight:700!important;font-size:17px!important;line-height:1!important;box-shadow:0 3px 12px #0007!important;padding:0!important;margin:0!important}
      #stAuthRoot .st-account-trigger{display:flex!important;align-items:center!important;justify-content:center!important;gap:0!important;width:42px!important;height:42px!important;min-width:42px!important;min-height:42px!important;max-width:42px!important;max-height:42px!important;overflow:visible!important;background:transparent!important;border:0!important;border-radius:50%!important;color:inherit!important;cursor:pointer!important;font:inherit!important;padding:1px!important;box-shadow:none!important}
      #stAuthRoot .st-account-trigger>span:not(.st-account-avatar),#stAuthRoot .st-account-trigger .username,#stAuthRoot .st-account-trigger .user-name{display:none!important}
      #stAuthRoot .st-account-menu{position:relative!important}
      #stAuthRoot .st-account-dropdown{position:absolute!important;right:0!important;left:auto!important;top:calc(100% + 6px)!important;min-width:150px!important;width:max-content!important;max-width:200px!important;height:auto!important;background:rgba(17,26,36,.78)!important;border:1px solid rgba(120,145,170,.35)!important;border-radius:12px!important;box-shadow:0 14px 38px rgba(0,0,0,.55)!important;padding:5px!important;margin:0!important;display:none!important;z-index:10040!important;overflow:visible!important;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px)}
      #stAuthRoot .st-account-dropdown.open{display:block!important}
      #stAuthRoot .st-account-dropdown a,#stAuthRoot .st-account-dropdown button{display:flex!important;align-items:center!important;gap:10px!important;width:100%!important;text-align:left!important;padding:9px 10px!important;margin:0!important;border:0!important;border-radius:8px!important;background:transparent!important;color:#eaf0f7!important;text-decoration:none!important;font:inherit!important;font-size:14px!important;line-height:1.3!important;cursor:pointer!important;box-sizing:border-box!important;box-shadow:none!important}
      #stAuthRoot .st-account-dropdown a:before{content:'◉';color:#c59bff;font-size:13px}
      #stAuthRoot .st-account-dropdown button:before{content:'↪';color:#c59bff;font-size:15px}
      #stAuthRoot .st-account-dropdown a:hover,#stAuthRoot .st-account-dropdown button:hover,#stAuthRoot .st-account-dropdown a:focus-visible,#stAuthRoot .st-account-dropdown button:focus-visible{background:rgba(153,91,207,.28)!important;outline:none!important}
      @media(max-width:760px){body>header,main>header,header{padding-right:112px!important}}
      @media(max-width:600px){#stAuthRoot{top:8px!important;right:8px!important}#stAuthRoot .st-login-top-button{padding:7px 11px!important}body>header,main>header,header{padding-right:104px!important}}
      .st-profile-page{max-width:760px;margin:28px auto;padding:24px;background:#101821;border:1px solid #293a4a;border-radius:16px;color:#eef2f7}.st-profile-page h1{margin-top:0}.st-profile-form{display:grid;gap:12px}.st-profile-form label{display:grid;gap:6px;color:#c6d2df;font-size:13px}.st-profile-form input{width:100%;box-sizing:border-box;padding:11px;border-radius:8px;border:1px solid #344456;background:#0b1118;color:#f4f7fb;font:inherit}.st-profile-form button,.st-profile-page button{padding:10px 13px;border-radius:8px;border:1px solid #344456;background:#182330;color:#eef2f7;cursor:pointer}.st-profile-form button.primary{background:#e8eef5;color:#101820;font-weight:700}.st-profile-note{color:#aebaca;font-size:13px}.st-profile-error{color:#ff9b9b}.st-profile-success{color:#73e6b0}
    `;document.head.appendChild(s);
  }
  function rememberReturn(){
    const here=location.pathname+location.search+location.hash;
    const safe=here.startsWith('/')&&!here.startsWith('//')&&!here.startsWith('/api/')?here:'/';try{localStorage.setItem(RETURN_KEY,safe)}catch(_){};document.cookie='stremng_return_to='+encodeURIComponent(safe)+'; Path=/; Max-Age=900; SameSite=Lax'
  }
  function ensureLoginModal(){
    addStyles();
    let modal=document.getElementById('stLoginModal');if(modal)return modal;
    modal=document.createElement('div');modal.id='stLoginModal';modal.className='st-login-modal';modal.setAttribute('role','dialog');modal.setAttribute('aria-modal','true');modal.setAttribute('aria-labelledby','stLoginTitle');
    modal.innerHTML='<section class="st-login-card"><button type="button" class="st-login-close" aria-label="Close">✕</button><h2 id="stLoginTitle">Welcome back</h2><p>Sign in to unlock historical charts and analytics.</p><div class="st-provider-row" role="group" aria-label="Sign in with a provider"><a class="st-provider-icon st-provider-wide st-login-google" href="/oauth2/authorization/google"><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="#4285F4" d="M21.6 12.23c0-.71-.06-1.39-.18-2.05H12v3.88h5.38a4.6 4.6 0 0 1-2 3.02v2.52h3.23c1.89-1.74 2.99-4.3 2.99-7.37Z"/><path fill="#34A853" d="M12 22c2.7 0 4.97-.9 6.62-2.4l-3.23-2.52c-.9.6-2.04.96-3.39.96-2.61 0-4.83-1.76-5.62-4.12H3.04v2.6A10 10 0 0 0 12 22Z"/><path fill="#FBBC05" d="M6.38 13.92A6 6 0 0 1 6.06 12c0-.67.12-1.32.32-1.92v-2.6H3.04A10 10 0 0 0 2 12c0 1.62.39 3.15 1.04 4.52l3.34-2.6Z"/><path fill="#EA4335" d="M12 5.96c1.47 0 2.79.5 3.83 1.51l2.87-2.87C16.96 2.97 14.7 2 12 2a10 10 0 0 0-8.96 5.48l3.34 2.6C7.17 7.72 9.39 5.96 12 5.96Z"/></svg><span>Continue with Google</span></a><div class="st-provider-more"><button type="button" class="st-provider-icon" aria-label="Discord login coming soon" title="Discord login coming soon" disabled><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="#5865F2" d="M19.73 5.04A18.8 18.8 0 0 0 15.1 3.6l-.57 1.16a17.2 17.2 0 0 0-5.06 0L8.9 3.6a18.7 18.7 0 0 0-4.63 1.44C1.34 9.36.55 13.58.94 17.74a18.8 18.8 0 0 0 5.68 2.87l1.23-2a12.2 12.2 0 0 1-1.94-.94l.47-.36c3.74 1.73 7.8 1.73 11.5 0l.49.36a12.3 12.3 0 0 1-1.95.94l1.23 2a18.7 18.7 0 0 0 5.68-2.87c.46-4.82-.78-8.99-3.6-12.7ZM8.6 14.8c-1.1 0-2-1.01-2-2.25s.88-2.25 2-2.25 2.02 1.02 2 2.25c0 1.24-.89 2.25-2 2.25Zm6.8 0c-1.1 0-2-1.01-2-2.25s.88-2.25 2-2.25 2.02 1.02 2 2.25c0 1.24-.89 2.25-2 2.25Z"/></svg><span>Discord</span><em class="st-soon">Soon</em></button><button type="button" class="st-provider-icon" aria-label="Twitch login coming soon" title="Twitch login coming soon" disabled><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="#9146FF" d="M4 2h18v13l-5 5h-4l-3 3v-3H4V2Zm2 2v14h5v2l2-2h4l3-3V4H6Zm5 3h2v6h-2V7Zm5 0h2v6h-2V7Z"/></svg><span>Twitch</span><em class="st-soon">Soon</em></button><button type="button" class="st-provider-icon" aria-label="Microsoft login coming soon" title="Microsoft login coming soon" disabled><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="#F25022" d="M2 2h9v9H2z"/><path fill="#7FBA00" d="M13 2h9v9h-9z"/><path fill="#00A4EF" d="M2 13h9v9H2z"/><path fill="#FFB900" d="M13 13h9v9h-9z"/></svg><span>Microsoft</span><em class="st-soon">Soon</em></button></div></div><div class="st-login-divider"><span>OR CONTINUE WITH EMAIL</span></div><form method="post" action="/login" class="st-login-form"><label for="stLoginIdentifier">Username or email</label><input id="stLoginIdentifier" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="Username or email" required><label for="stLoginPassword">Password</label><div class="st-pass"><input id="stLoginPassword" name="password" type="password" autocomplete="current-password" placeholder="Your password" required><button type="button" class="st-pass-toggle" aria-label="Show password" aria-pressed="false">Show</button></div><div class="st-login-error" role="alert" aria-live="assertive"></div><div class="st-login-actions"><button class="primary" type="submit">Log in</button></div></form><p class="st-login-switch">New to Stremng? <button type="button" class="st-login-signup">Create an account</button></p></section>';
    document.body.appendChild(modal);
    const close=()=>{modal.classList.remove('open');if(lastFocus&&lastFocus.focus)lastFocus.focus()};modal.querySelector('.st-login-close').addEventListener('click',close);modal.addEventListener('click',e=>{if(e.target===modal)close()});
    const loginForm=modal.querySelector('form'),loginBtn=loginForm.querySelector('button.primary');
    loginForm.addEventListener('submit',()=>{
      if(!isLoginRoute())rememberReturn();
      try{sessionStorage.setItem(LOGIN_PENDING_KEY,'1')}catch(_){}
      loginBtn.disabled=true;loginBtn.textContent='Signing in\u2026';
    });
    window.addEventListener('pageshow',()=>{loginBtn.disabled=false;loginBtn.textContent='Log in'});
    const passInput=modal.querySelector('#stLoginPassword'),passToggle=modal.querySelector('.st-pass-toggle');
    passToggle.addEventListener('click',()=>{
      const show=passInput.type==='password';
      passInput.type=show?'text':'password';
      passToggle.textContent=show?'Hide':'Show';
      passToggle.setAttribute('aria-pressed',String(show));
      passToggle.setAttribute('aria-label',show?'Hide password':'Show password');
    });
    // Keep keyboard focus inside the dialog while it is open.
    modal.addEventListener('keydown',e=>{
      if(e.key!=='Tab')return;
      const f=[...modal.querySelectorAll('a[href],button:not([disabled]),input')].filter(el=>el.offsetParent!==null);
      if(!f.length)return;
      const first=f[0],last=f[f.length-1];
      if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus()}
      else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus()}
    });
    modal.querySelector('.st-login-google').addEventListener('click',e=>{e.preventDefault();if(!isLoginRoute())rememberReturn();location.href='/oauth2/authorization/google'});
    modal.querySelector('.st-login-signup').addEventListener('click',()=>{close();if(typeof window.stOpenSignup==='function')window.stOpenSignup();else {location.href='/?auth=signup'}});
    document.addEventListener('keydown',e=>{if(e.key==='Escape')close()});
    return modal;
  }
  function openLoginModal(){
    lastFocus=document.activeElement;
    const modal=ensureLoginModal();
    // A failed password login redirects back here with ?auth=login; show why.
    const err=modal.querySelector('.st-login-error');
    let pending=false;try{pending=sessionStorage.getItem(LOGIN_PENDING_KEY)==='1';sessionStorage.removeItem(LOGIN_PENDING_KEY)}catch(_){}
    err.textContent=(pending&&isLoginRoute())?'Incorrect username/email or password. Please try again.':'';
    modal.classList.add('open');
    setTimeout(()=>modal.querySelector('input[name="username"]').focus(),0);
  }
  document.addEventListener('click',e=>{
    const a=e.target.closest('a');if(!a)return;
    const href=a.getAttribute('href')||'';
    if(href==='/login'||href.endsWith('/login')){e.preventDefault();rememberReturnAndGo('/login');return}
    if(href==='/?auth=signup'||href.includes('auth=signup')){e.preventDefault();const signup=[...document.querySelectorAll('#stAuthBar button')].find(b=>/sign up/i.test(b.textContent));if(signup)signup.click();else location.href='/?auth=signup'}
  });
  function isLoginRoute(){return location.pathname==='/login'||location.pathname==='/login.html'||new URLSearchParams(location.search).get('auth')==='login'}
  function rememberReturnAndGo(url){
    let here=location.pathname+location.search+location.hash;
    if(new URLSearchParams(location.search).get('auth')==='login')here=location.pathname+location.hash;
    if(location.pathname==='/login.html'||location.pathname==='/login')here='/';
    const safe=here.startsWith('/')&&!here.startsWith('//')&&!here.startsWith('/api/')?here:'/';try{localStorage.setItem(RETURN_KEY,safe)}catch(_){};document.cookie='stremng_return_to='+encodeURIComponent(safe)+'; Path=/; Max-Age=900; SameSite=Lax'
    location.href=url;
  }
  function addDemoBackground(panel){
    if(panel.querySelector(':scope > .guest-demo-background'))return;
    const bg=document.createElement('div');bg.className='guest-demo-background';bg.setAttribute('aria-hidden','true');
    const bars=panel.matches('.peak-card,.stat')||/match|peak|channelhistory/i.test(panel.id||'')||panel.querySelector('table');
    let svg='';
    if(bars){
      const heights=[30,48,40,66,55,77,62,86,70,93,74,82,58,88,68,96];
      svg=`<svg viewBox="0 0 400 120" preserveAspectRatio="none"><defs><linearGradient id="guestDemoGradient" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#56b4ef"/><stop offset="1" stop-color="#56b4ef" stop-opacity="0"/></linearGradient></defs>${[20,45,70,95].map(y=>`<path class="demo-grid" d="M0 ${y}H400"/>`).join('')}${heights.map((h,i)=>`<rect class="demo-bars" x="${i*24+4}" y="${112-h}" width="14" height="${h}" rx="2" style="opacity:${.4+(i%4)*.12}"/>`).join('')}</svg>`;
    }else{
      const area=SAMPLE_LINE+' L380 112 L0 112 Z';
      svg=`<svg viewBox="0 0 400 120" preserveAspectRatio="none"><defs><linearGradient id="guestDemoGradient" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#56b4ef" stop-opacity=".7"/><stop offset="1" stop-color="#56b4ef" stop-opacity="0"/></linearGradient></defs>${[20,45,70,95].map(y=>`<path class="demo-grid" d="M0 ${y}H400"/>`).join('')}<path class="demo-area" d="${area}"/><path class="demo-line" d="${SAMPLE_LINE}"/><path class="demo-line" stroke="#a970ff" d="M0 100 C35 88 60 96 90 82 S150 75 180 83 S240 62 270 74 S330 55 380 66"/></svg>`;
    }
    bg.innerHTML=svg;
    panel.insertBefore(bg,panel.firstChild);
    // For compact peak/stat cards show an illustrative number, never a real value.
    if(panel.matches('.peak-card,.stat')){
      const sample=document.createElement('div');sample.className='guest-demo-sample';sample.setAttribute('aria-hidden','true');
      sample.textContent=panel.matches('.peak-card')?'248,600':'1,284,500';
      sample.style.cssText='position:absolute;left:48%;top:42%;z-index:1;transform:translate(-50%,-50%);font-size:clamp(15px,2vw,24px);font-weight:800;color:#dce8f6;filter:blur(4px);pointer-events:none';
      panel.appendChild(sample);
    }
  }
  function lockPanel(panel){
    if(!panel||panel.dataset.guestHistoryLocked==='true')return;
    panel.dataset.guestHistoryLocked='true';panel.classList.add('guest-history-locked');
    if(panel.matches('.peak-card,.stat'))panel.classList.add('guest-lock-compact');
    addDemoBackground(panel);
    const overlay=document.createElement('div');overlay.className='guest-history-lock-overlay';
    const icon=document.createElement('div');icon.className='guest-history-lock-icon';icon.textContent='🔒';icon.setAttribute('aria-hidden','true');
    const title=document.createElement('strong');title.textContent='Historical data is for members';
    const description=document.createElement('span');description.textContent='Sign in to view historical data, trends, and comparisons.';
    const actions=document.createElement('div');actions.className='guest-history-lock-actions';
    const addLink=(label,url,primary=false)=>{const a=document.createElement('a');a.href=url;a.textContent=label;if(primary)a.className='primary';a.addEventListener('click',e=>{e.preventDefault();rememberReturnAndGo(url)});actions.appendChild(a)};
    addLink('Login','/login',true);
    overlay.append(icon,title,description,actions);panel.appendChild(overlay);
  }
  function applyMatchMetricLocks(){
    if((location.pathname.split('/').pop()||'').toLowerCase()!=='game.html')return;
    let accountAuthenticated=false;
    // This is a visual privacy layer only; the backend must continue enforcing API permissions.
    const ids=['matchQuickYoutube','matchQuickTwitch','matchQuickKick'];
    ids.forEach(id=>{
      const value=document.getElementById(id);
      const card=value?.closest('.match-metric');
      if(!card||card.querySelector('.guest-metric-icon-lock'))return;
      card.classList.add('guest-metric-locked');
      const lock=document.createElement('span');lock.className='guest-metric-icon-lock';lock.textContent='🔒';lock.setAttribute('aria-label','Member-only viewership');
      card.appendChild(lock);
    });
  }
  addStyles();
  let authBarObserverStarted=false;
  async function renderAccountBar(){
    addStyles();
    const bar=document.getElementById('stAuthBar'); if(!bar)return;
    if(bar.dataset.stRendered==='yes')return;
    bar.dataset.stRendered='yes';
    let me={authenticated:false};try{const r=await fetch(API_ME,{credentials:'same-origin',cache:'no-store'});if(r.ok)me=await r.json()}catch(_){}
    if(me.authenticated&&!me.pendingUsername){
      bar.replaceChildren();bar.classList.add('st-auth-bar');
      const wrap=document.createElement('div');wrap.className='st-account-menu';
      const trigger=document.createElement('button');trigger.type='button';trigger.className='st-account-trigger';trigger.setAttribute('aria-haspopup','true');trigger.setAttribute('aria-expanded','false');
      if(me.picture){const img=document.createElement('img');img.className='st-account-avatar';img.src=me.picture;img.alt='';trigger.appendChild(img)}else{const avatar=document.createElement('span');avatar.className='st-account-avatar';avatar.textContent=(me.name||me.username||'U').trim().charAt(0).toUpperCase();trigger.appendChild(avatar)}
      const dropdown=document.createElement('div');dropdown.className='st-account-dropdown';
      const profile=document.createElement('a');profile.href='/profile.html';profile.textContent='Profile';dropdown.appendChild(profile);
      const signout=document.createElement('button');signout.type='button';signout.textContent='Sign out';signout.addEventListener('click',async()=>{signout.disabled=true;try{const r=await fetch('/api/auth/logout',{method:'POST',credentials:'same-origin'});if(!r.ok)throw new Error();location.reload()}catch(_){signout.disabled=false;alert('Could not sign out. Please try again.')}});dropdown.appendChild(signout);
      trigger.addEventListener('click',()=>{const open=dropdown.classList.toggle('open');trigger.setAttribute('aria-expanded',String(open))});document.addEventListener('click',e=>{if(!wrap.contains(e.target)){dropdown.classList.remove('open');trigger.setAttribute('aria-expanded','false')}});
      wrap.append(trigger,dropdown);bar.appendChild(wrap);
    }else{
      bar.replaceChildren();const login=document.createElement('button');login.type='button';login.className='st-login-top-button';login.textContent='Login';login.addEventListener('click',openLoginModal);bar.appendChild(login);
    }
  }
  function watchAccountBar(){
    const bar=document.getElementById('stAuthBar');if(!bar||authBarObserverStarted)return;authBarObserverStarted=true;
    const obs=new MutationObserver(()=>{if(bar.querySelector('.st-account-menu,.st-login-top-button'))return;bar.dataset.stRendered='';renderAccountBar()});obs.observe(bar,{childList:true,subtree:true});
    renderAccountBar();
  }
  async function apply(){
    addStyles();
    const page=(location.pathname.split('/').pop()||'index.html').toLowerCase();
    // Only restore a saved return page on root after authentication succeeded.
    try{
      const saved=localStorage.getItem(RETURN_KEY);
      if(saved&&(page==='index.html'||location.pathname==='/')){
        const r=await fetch(API_ME,{credentials:'same-origin',cache:'no-store'}).catch(()=>null);
        const account=r&&r.ok?await r.json():{authenticated:false};
        if(account.authenticated&&!account.pendingUsername&&saved.startsWith('/')&&!saved.startsWith('//')&&!saved.startsWith('/api/')&&saved!=='/'&&saved!==(location.pathname+location.search+location.hash)){
          localStorage.removeItem(RETURN_KEY);location.replace(saved);return;
        }
        if((!account.authenticated&&new URLSearchParams(location.search).get('auth')!=='login')||saved===(location.pathname+location.search+location.hash))localStorage.removeItem(RETURN_KEY);
      }
    }catch(_){}
    watchAccountBar();
    if(new URLSearchParams(location.search).get('auth')==='login'){
      // Only signed-out visitors (or users still choosing a username) should see the login card.
      let acct={authenticated:false};
      try{const r=await fetch(API_ME,{credentials:'same-origin',cache:'no-store'});if(r.ok)acct=await r.json()}catch(_){}
      if(acct.authenticated&&!acct.pendingUsername){
        const u=new URL(location.href);u.searchParams.delete('auth');
        history.replaceState(null,'',u.pathname+u.search+u.hash);
      }else{openLoginModal()}
    }
    const selectors=routeLocks[page]||[];
    if(page==='game.html'){
      let account={authenticated:false};
      try{const r=await fetch(API_ME,{credentials:'same-origin',cache:'no-store'});if(r.ok)account=await r.json()}catch(_){}
      if(account.authenticated&&!account.pendingUsername)return;
      addStyles();applyMatchMetricLocks();
      const observer=new MutationObserver(()=>applyMatchMetricLocks());
      observer.observe(document.getElementById('activeMatchDetail')||document.body,{childList:true,subtree:true});
    }
    if(!selectors.length)return;
    let account={authenticated:false};
    try{const r=await fetch(API_ME,{credentials:'same-origin',cache:'no-store'});if(r.ok)account=await r.json()}catch(_){}
    if(account.authenticated&&!account.pendingUsername)return;
    addStyles();
    const targets=new Set();selectors.forEach(sel=>document.querySelectorAll(sel).forEach(el=>targets.add(el)));
    targets.forEach(lockPanel);
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',apply);else apply();
})();
