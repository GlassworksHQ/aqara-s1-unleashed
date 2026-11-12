// The paint of drawn devices: the recovery view's case, board, screws and USB-C plug, and the frame
// cards' front glass and mirror silvering. These depict hardware, not the brand, so they are the one
// place in Kiln with literal colours (test/no-hex.test.ts). A frame's own side colours come from its
// frame.json.
export const MATERIAL = {
  caseA: '#2a2d36',
  caseB: '#1a1c23',
  caseSide: '#101116',
  boardBed: '#0c0d11',
  dark: '#050506',
  rib: '#22252d',
  ribLine: '#2c2f38',
  plate: '#262932',
  hole: '#1e2028',
  clip: '#0b0c10',
  buttonCap: '#9a9ca3',
  button: '#2b2c33',
  screw: '#c6c9cf',
  screwEdge: '#8d9199',
  slot: '#5f636b',
  cable: '#2a2d35',
  boot: '#30333c',
  pins: '#c8cbd2',
  plugA: '#6b6f7a',
  plugB: '#3d414b',
  plugC: '#2a2d35',
  sweep: '#e4e4e7',
  /* the frame cards' 3D mockup (glassworks.dev DeviceModel.module.css) */
  glass: '#07080b',
  mirrorA: '#3d4048',
  mirrorB: '#1a1c21',
  mirrorC: '#31343b',
} as const;
