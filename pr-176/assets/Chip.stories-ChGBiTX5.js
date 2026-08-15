import{n as e}from"./rolldown-runtime-DkW27tQK.js";import{f as t,n}from"./iframe-DMpwJcfg.js";import{n as r,t as i}from"./flame-D-980mUE.js";import{n as a,r as o,t as s}from"./Chip-qHsOtV-n.js";import{r as c,t as l}from"./sample-data-CJojLpxe.js";var u,d,f,p,m,h,g,_,v;function y(){return(y=e((()=>{u=t(),r(),o(),c(),d=n(),f={title:`Primitives/Chip`,component:s,parameters:{docs:{description:{component:`The selected state is **white**, not lime — "urgent" is meaningless for a filter.`}}},args:{children:`Technology`}},p={args:{active:!1}},m={args:{active:!0}},h={args:{count:24}},g={parameters:{layout:`padded`},render:function(){let[e,t]=(0,u.useState)(`All`);return(0,d.jsxs)(`div`,{className:`w-[600px]`,children:[(0,d.jsx)(a,{children:l.map(n=>(0,d.jsx)(s,{active:e===n,onClick:()=>t(n),children:n},n))}),(0,d.jsxs)(`p`,{className:`mt-4 text-sm text-white/40`,children:[`Selected: `,e]})]})}},_={parameters:{layout:`padded`},render:()=>(0,d.jsxs)(a,{fadeEdge:!1,children:[(0,d.jsx)(s,{active:!0,count:2534,children:`Live`}),(0,d.jsx)(s,{count:11054,children:`Upcoming`}),(0,d.jsx)(s,{count:4736,children:`Late pledge`}),(0,d.jsx)(s,{icon:(0,d.jsx)(i,{className:`size-3.5 text-hot`}),children:`Trending`})]})},p.parameters={...p.parameters,docs:{...p.parameters?.docs,source:{originalSource:`{
  args: {
    active: false
  }
}`,...p.parameters?.docs?.source}}},m.parameters={...m.parameters,docs:{...m.parameters?.docs,source:{originalSource:`{
  args: {
    active: true
  }
}`,...m.parameters?.docs?.source}}},h.parameters={...h.parameters,docs:{...h.parameters?.docs,source:{originalSource:`{
  args: {
    count: 24
  }
}`,...h.parameters?.docs?.source}}},g.parameters={...g.parameters,docs:{...g.parameters?.docs,source:{originalSource:`{
  parameters: {
    layout: 'padded'
  },
  render: function RowStory() {
    const [active, setActive] = useState<string>('All');
    return <div className="w-[600px]">
        <ChipRow>
          {SAMPLE_CATEGORIES.map(c => <Chip key={c} active={active === c} onClick={() => setActive(c)}>
              {c}
            </Chip>)}
        </ChipRow>
        <p className="mt-4 text-sm text-white/40">Selected: {active}</p>
      </div>;
  }
}`,...g.parameters?.docs?.source},description:{story:`Horizontally scrolling filter row with a fading right edge.`,...g.parameters?.docs?.description}}},_.parameters={..._.parameters,docs:{..._.parameters?.docs,source:{originalSource:`{
  parameters: {
    layout: 'padded'
  },
  render: () => <ChipRow fadeEdge={false}>
      <Chip active count={2534}>
        Live
      </Chip>
      <Chip count={11054}>Upcoming</Chip>
      <Chip count={4736}>Late pledge</Chip>
      <Chip icon={<Flame className="size-3.5 text-hot" />}>Trending</Chip>
    </ChipRow>
}`,..._.parameters?.docs?.source}}},v=[`Default`,`Active`,`WithCount`,`Row`,`WithIconAndCount`]})))()}y();export{m as Active,p as Default,g as Row,h as WithCount,_ as WithIconAndCount,v as __namedExportsOrder,f as default};