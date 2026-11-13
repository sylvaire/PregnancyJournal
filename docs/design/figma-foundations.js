/**
 * createVariableCollection
 *
 * Creates a new Figma variable collection with the specified name and modes.
 * If `modeNames` has more than one entry, the first mode is renamed from
 * Figma's default "Mode 1" to the first name, and additional modes are added.
 *
 * @param {string} name - The display name of the collection (e.g. "Color", "Spacing").
 * @param {string[]} modeNames - Ordered list of mode names (e.g. ["Light", "Dark"] or ["Value"]).
 * @returns {Promise<{
 *   collection: VariableCollection,
 *   modeIds: Record<string, string>
 * }>}
 *   `modeIds` maps each mode name to its modeId string.
 */
async function createVariableCollection(name, modeNames) {
  if (!modeNames || modeNames.length === 0) {
    throw new Error('createVariableCollection: modeNames must have at least one entry.')
  }

  // Create the collection — Figma always creates it with one mode named "Mode 1".
  const collection = figma.variables.createVariableCollection(name)

  // modeIds accumulator
  const modeIds = {}

  // Rename the default first mode
  const defaultMode = collection.modes[0]
  collection.renameMode(defaultMode.modeId, modeNames[0])
  modeIds[modeNames[0]] = defaultMode.modeId

  // Add additional modes
  for (let i = 1; i < modeNames.length; i++) {
    const newModeId = collection.addMode(modeNames[i])
    modeIds[modeNames[i]] = newModeId
  }

  return { collection, modeIds }
}

/**
 * createSemanticTokens
 *
 * Creates a batch of Figma variables in the given collection, one per entry in
 * `tokenMap`. Supports raw values, variable alias references, code syntax, and
 * scopes. Returns a map of token name → Variable for use in subsequent steps.
 *
 * @param {VariableCollection} collection - The target variable collection.
 * @param {Record<string, string>} modeIds - Map of {modeName: modeId} from createVariableCollection.
 * @param {Array<{
 *   name: string,
 *   type: 'COLOR' | 'FLOAT' | 'STRING' | 'BOOLEAN' | 'TIMING' | 'EASING',
 *   values: Record<string, string | number | boolean | MotionEasing | {type: 'VARIABLE_ALIAS', id: string}>,
 *   scopes?: VariableScope[],
 *   codeSyntax?: {WEB?: string, ANDROID?: string, iOS?: string}
 * }>} tokenMap - Ordered list of token definitions.
 *   - `name`: Variable name using slash hierarchy (e.g. "color/bg/primary").
 *   - `type`: Figma variable type.
 *   - `values`: Map of {modeName: value}. Values can be raw (hex string for COLOR,
 *     number for FLOAT) or alias objects {type: 'VARIABLE_ALIAS', id: variableId}.
 *     For COLOR, raw values are accepted as hex strings ("#rrggbb" or "#rrggbbaa")
 *     and converted to {r, g, b, a} automatically.
 *   - `scopes`: Array of VariableScope strings. Omit to use [] (hidden/primitive).
 *   - `codeSyntax`: Platform code syntax strings. Omit to skip.
 * @returns {Promise<{variables: Record<string, Variable>}>}
 *   `variables` maps each token name to its created Variable object.
 */
async function createSemanticTokens(collection, modeIds, tokenMap) {
  const variables = {}

  for (const token of tokenMap) {
    // Create the variable
    const variable = figma.variables.createVariable(token.name, collection, token.type)

    // Set values for each mode
    for (const [modeName, rawValue] of Object.entries(token.values)) {
      const modeId = modeIds[modeName]
      if (!modeId) {
        throw new Error(
          `createSemanticTokens: mode "${modeName}" not found in modeIds for token "${token.name}". ` +
            `Available modes: ${Object.keys(modeIds).join(', ')}`,
        )
      }

      let value = rawValue

      // Convert hex strings to Figma RGBA for COLOR type
      if (token.type === 'COLOR' && typeof rawValue === 'string' && rawValue.startsWith('#')) {
        value = hexToFigmaColor(rawValue)
      }

      variable.setValueForMode(modeId, value)
    }

    // Set scopes (default: empty array = hidden from property pickers / primitives)
    variable.scopes = token.scopes || []

    // Set code syntax per platform
    if (token.codeSyntax) {
      if (token.codeSyntax.WEB) {
        variable.setVariableCodeSyntax('WEB', token.codeSyntax.WEB)
      }
      if (token.codeSyntax.ANDROID) {
        variable.setVariableCodeSyntax('ANDROID', token.codeSyntax.ANDROID)
      }
      if (token.codeSyntax.iOS) {
        variable.setVariableCodeSyntax('iOS', token.codeSyntax.iOS)
      }
    }

    variables[token.name] = variable
  }

  return { variables }
}

/**
 * Converts a hex color string to a Figma RGBA object.
 * Supports "#rgb", "#rrggbb", and "#rrggbbaa".
 *
 * @param {string} hex
 * @returns {{ r: number, g: number, b: number, a: number }}
 */
function hexToFigmaColor(hex) {
  let h = hex.replace('#', '')

  // Expand shorthand #rgb → #rrggbb
  if (h.length === 3) {
    h = h
      .split('')
      .map((c) => c + c)
      .join('')
  }

  const r = parseInt(h.substring(0, 2), 16) / 255
  const g = parseInt(h.substring(2, 4), 16) / 255
  const b = parseInt(h.substring(4, 6), 16) / 255
  const a = h.length === 8 ? parseInt(h.substring(6, 8), 16) / 255 : 1

  return { r, g, b, a }
}


await figma.setCurrentPageAsync(figma.root.children[0]);
const page=figma.currentPage;page.name='01 · 孕迹 V1.0';
if(page.children.some(n=>n.name==='孕迹 · 设计基础')) throw new Error('Foundations already exist; inspect before update');
await Promise.all(['Regular','Medium','Bold'].map(style=>figma.loadFontAsync({family:'Noto Sans SC',style})));
const {collection,modeIds}=await createVariableCollection('孕迹 · Light',['Value']);
const palette={bg:'#F7F8F3',surface:'#FFFFFF',ink:'#223C32',muted:'#65766D',primary:'#356B53',soft:'#E7F0E6',line:'#DDE4DA',apricot:'#FAEAD8',amber:'#80582C',danger:'#A44238',dangerSoft:'#FCECE7'};
const tokens=[];
for(const [key,value] of Object.entries(palette)){tokens.push({name:'primitive/'+key,type:'COLOR',values:{Value:value},scopes:[],codeSyntax:{ANDROID:'PregnancyColors.'+key}});}
const {variables:primitives}=await createSemanticTokens(collection,modeIds,tokens);
const {variables:colors}=await createSemanticTokens(collection,modeIds,Object.keys(palette).map(key=>({name:'color/'+key,type:'COLOR',values:{Value:{type:'VARIABLE_ALIAS',id:primitives['primitive/'+key].id}},scopes:['FRAME_FILL','SHAPE_FILL','TEXT_FILL','STROKE_COLOR'],codeSyntax:{ANDROID:'PregnancyColors.'+key}})));
const nums=[... [4,8,12,16,20,24,32].map(n=>({name:'space/'+n,type:'FLOAT',values:{Value:n},scopes:['GAP'],codeSyntax:{ANDROID:n+'.dp'}})),...[12,20,28].map(n=>({name:'radius/'+n,type:'FLOAT',values:{Value:n},scopes:['CORNER_RADIUS'],codeSyntax:{ANDROID:n+'.dp'}}))];
const {variables:dimensions}=await createSemanticTokens(collection,modeIds,nums);
const ids=[]; const styles={};
for(const [name,size,weight] of [['display',40,'Bold'],['title',28,'Bold'],['heading',20,'Medium'],['body',16,'Regular'],['label',14,'Medium'],['caption',12,'Regular']]){
const s=figma.createTextStyle();s.name='孕迹/'+name;s.fontName={family:'Noto Sans SC',style:weight};s.fontSize=size;s.lineHeight={unit:'PIXELS',value:Math.round(size*1.5)};styles[name]=s.id;}
function paint(key){return figma.variables.setBoundVariableForPaint({type:'SOLID',color:{r:0,g:0,b:0}},'color',colors['color/'+key]);}
function fill(n,key){n.fills=[paint(key)];}
function txt(parent,text,style='body',color='ink'){const n=figma.createText();parent.appendChild(n);n.characters=text;n.textStyleId=styles[style];fill(n,color);ids.push(n.id);return n;}
function box(parent,name,w,dir='VERTICAL',bg=null,pad=0,gap=8){const n=figma.createAutoLayout(dir);parent.appendChild(n);n.name=name;n.resize(w,10);n.primaryAxisSizingMode='AUTO';n.counterAxisSizingMode='FIXED';n.itemSpacing=gap;n.paddingTop=n.paddingBottom=n.paddingLeft=n.paddingRight=pad;if(bg)fill(n,bg);else n.fills=[];ids.push(n.id);return n;}
const foundation=box(page,'孕迹 · 设计基础',1840,'VERTICAL','surface',32,24);foundation.x=100;foundation.y=100;
txt(foundation,'孕迹  /  轻轻记录，安心留存','title');
txt(foundation,'V1.0 · Android UI    /    Noto Sans SC    /    412 × 915','body','muted');
const swatches=box(foundation,'品牌色',1776,'HORIZONTAL',null,0,16);
for(const key of ['primary','soft','bg','ink','apricot','danger']){const sw=box(swatches,key,270,'VERTICAL',key,16,8);txt(sw,key,'label',key==='primary'||key==='ink'?'surface':'ink');}
const textRow=box(foundation,'Typography',1776,'HORIZONTAL',null,0,24);for(const style of ['title','heading','body','caption'])txt(textRow,style+' 孕迹',style);
const bset=await figma.importComponentSetByKeyAsync('ab924dce8b851fd820bd0d56da24a9c489311ae6');
const fset=await figma.importComponentSetByKeyAsync('a54980c1c99c62912ab39e9e30fe5c267c98edb4');
const bv=bset.children.find(x=>x.name==='Type=Round, Size=Small, State=Enabled');
const fv=fset.children.find(x=>x.name==='Style=Outlined, State=Enabled, Text configurations=Input text, Leading icon=False, Trailing icon=False');
for(const root of [bv,fv])for(const t of root.findAll(n=>n.type==='TEXT'))for(const seg of t.getStyledTextSegments(['fontName']))await figma.loadFontAsync(seg.fontName);
const compArea=box(page,'孕迹 · 基础组件',1840,'HORIZONTAL','surface',32,24);compArea.x=100;compArea.y=650;
const buttons=[];
for(const state of ['Primary','Secondary','Disabled']){const c=figma.createComponent();compArea.appendChild(c);c.name='Style='+state;c.layoutMode='VERTICAL';c.resize(348,52);c.primaryAxisSizingMode='FIXED';c.counterAxisSizingMode='FIXED';c.fills=[];
const i=bv.createInstance();c.appendChild(i);i.setProperties({'Show icon#58653:51':false,'Label text#58653:0':state==='Disabled'?'请先完成必填项':'保存记录'});i.resize(348,52);i.layoutSizingHorizontal='FILL';
for(const n of i.findAll(n=>n.type==='TEXT')){n.fontName={family:'Noto Sans SC',style:'Medium'};n.textStyleId=styles.body;fill(n,state==='Primary'?'surface':state==='Secondary'?'primary':'muted');}
const content=i.findOne(n=>n.name==='Content'&&n.type==='FRAME');if(content)fill(content,state==='Primary'?'primary':state==='Secondary'?'soft':'line');buttons.push(c);ids.push(c.id,...c.findAll(()=>true).map(n=>n.id));}
const buttonSet=figma.combineAsVariants(buttons,compArea);buttonSet.name='孕迹/Button';buttonSet.description='Material 3 Button 实例封装；中文字体及品牌配色；Primary、Secondary、Disabled。';buttonSet.layoutMode='VERTICAL';buttonSet.itemSpacing=16;buttonSet.paddingTop=buttonSet.paddingBottom=buttonSet.paddingLeft=buttonSet.paddingRight=16;buttonSet.primaryAxisSizingMode='AUTO';buttonSet.counterAxisSizingMode='AUTO';ids.push(buttonSet.id);
const field=figma.createComponent();compArea.appendChild(field);field.name='孕迹/Field';field.description='Material 3 outlined text field；支持中文标签、输入值与辅助文字。';field.layoutMode='VERTICAL';field.resize(348,60);field.primaryAxisSizingMode='AUTO';field.counterAxisSizingMode='FIXED';field.fills=[];
const fi=fv.createInstance();field.appendChild(fi);fi.setProperties({'Show supporting text#52051:0':false,'Label text#52808:484':'字段名称','Input text#52808:726':'填写内容'});fi.resize(348,56);fi.layoutSizingHorizontal='FILL';
for(const n of fi.findAll(n=>n.type==='TEXT')){n.fontName={family:'Noto Sans SC',style:'Regular'};fill(n,'ink');}ids.push(field.id,...field.findAll(()=>true).map(n=>n.id));
const row=figma.createComponent();compArea.appendChild(row);row.name='孕迹/RecordRow';row.description='资料与事项行，标题、次级说明和尾标可覆盖。';row.layoutMode='HORIZONTAL';row.resize(348,84);row.primaryAxisSizingMode='FIXED';row.counterAxisSizingMode='AUTO';row.paddingTop=row.paddingBottom=row.paddingLeft=row.paddingRight=16;row.itemSpacing=12;row.counterAxisAlignItems='CENTER';fill(row,'surface');row.cornerRadius=20;
const icon=box(row,'Icon',44,'VERTICAL','soft',8,0);txt(icon,'＋','heading','primary');const body=box(row,'Body',226,'VERTICAL',null,0,4);txt(body,'记录标题','body');txt(body,'日期 · 记录说明','caption','muted');txt(row,'›','heading','muted');ids.push(row.id);
const result={createdNodeIds:ids,mutatedNodeIds:[page.id],pageId:page.id,foundationId:foundation.id,componentAreaId:compArea.id,components:{buttonSet:buttonSet.id,buttons:buttons.map(n=>({id:n.id,name:n.name})),field:field.id,row:row.id},styles,variables:Object.fromEntries(Object.entries({...colors,...dimensions}).map(([k,v])=>[k,v.id])),font:'Noto Sans SC',checks:{tokens:Object.keys(colors).length+Object.keys(dimensions).length,styles:Object.keys(styles).length}};
return result;
