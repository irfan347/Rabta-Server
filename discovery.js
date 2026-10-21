// Authenticated, bounded discovery. Phone numbers are self-reported, never identity proof.
module.exports = function discovery({app, authId, users, blocked, getPool}) {
  const budgets = new Map();
  const number = v => typeof v === 'string' && /^\+[1-9][0-9]{7,14}$/.test(v);
  function guard(req, res) {
    const me = authId(req);
    if (!me || !users.has(me)) { res.status(401).json({error:'Sign in first'}); return null; }
    const now=Date.now();
    for(const [id,b] of budgets) if(b.until<=now) budgets.delete(id);
    const b=budgets.get(me)||{count:0,until:now+60000};
    if(b.count>=30 || (!budgets.has(me)&&budgets.size>=10000)) { res.status(429).json({error:'Please wait before searching again'});return null; }
    b.count++;budgets.set(me,b);return me;
  }
  const publicUser = u => ({id:u.id,username:u.username,name:u.name});
  app.get('/api/contacts/search',(req,res)=>{
    const me=guard(req,res);if(!me)return;
    const q=String(req.query?.q||'').trim().toLowerCase();
    if(!/^[a-z0-9_]{2,20}$/.test(q))return res.status(400).json({error:'Type at least two username characters'});
    const matches=[...users.values()].filter(u=>u.id!==me&&!blocked(me,u.id)&&u.username.startsWith(q))
      .sort((a,b)=>a.username.localeCompare(b.username)).slice(0,20).map(publicUser);
    res.json({users:matches});
  });
  app.post('/api/contacts/match',(req,res)=>{
    const me=guard(req,res);if(!me)return;
    const numbers=req.body?.numbers;
    if(!Array.isArray(numbers)||numbers.length>500||!numbers.every(number))return res.status(400).json({error:'Send up to 500 international phone numbers'});
    const wanted=new Set(numbers);
    const matches=[...users.values()].filter(u=>u.id!==me&&u.phone_discoverable===true&&wanted.has(u.phone_number)&&!blocked(me,u.id)).slice(0,500)
      .map(u=>({...publicUser(u),matchedNumber:u.phone_number,verified:false}));
    res.json({users:matches});
  });
  app.get('/api/me/contact-details',(req,res)=>{
    const me=guard(req,res);if(!me)return;const u=users.get(me);
    res.json({phoneNumber:u.phone_number||'',discoverable:u.phone_discoverable===true,verified:false});
  });
  app.post('/api/me/contact-details',async(req,res)=>{
    const me=guard(req,res);if(!me)return;
    const {phoneNumber,discoverable}=req.body||{};
    if(!number(phoneNumber)||typeof discoverable!=='boolean')return res.status(400).json({error:'Enter an international number and discovery preference'});
    try {
      const db=getPool();if(db)await db.query('UPDATE ru_users SET phone_number=$1,phone_discoverable=$2 WHERE id=$3',[phoneNumber,discoverable,me]);
      Object.assign(users.get(me),{phone_number:phoneNumber,phone_discoverable:discoverable});res.json({ok:true,verified:false});
    } catch { res.status(503).json({error:'Could not save contact details'}); }
  });
};
